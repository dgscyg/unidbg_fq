#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import os
import random
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List

import psycopg

from fq_device_register import (
    EnhancedDeviceRegisterClient,
    ImprovedRandomDeviceGenerator,
    generate_xml_config,
    save_results,
)

REPO_ROOT = Path(__file__).resolve().parents[1]

CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS device_pool (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128),
    user_agent TEXT NOT NULL,
    cookie TEXT NOT NULL,
    cdid VARCHAR(128),
    install_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    aid VARCHAR(32) NOT NULL,
    version_code VARCHAR(32),
    version_name VARCHAR(64),
    update_version_code VARCHAR(32) NOT NULL,
    device_type VARCHAR(128),
    device_brand VARCHAR(128),
    rom_version VARCHAR(255),
    resolution VARCHAR(64),
    dpi VARCHAR(32),
    host_abi VARCHAR(64),
    os_version VARCHAR(32),
    os_api VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    failure_count INTEGER NOT NULL DEFAULT 0,
    cooldown_until TIMESTAMPTZ,
    last_failure_reason VARCHAR(255),
    last_failure_at TIMESTAMPTZ,
    last_selected_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    source VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_pool_identity UNIQUE (device_id, install_id),
    CONSTRAINT uq_device_pool_name UNIQUE (name)
)
"""

CREATE_AVAILABLE_INDEX_SQL = """
CREATE INDEX IF NOT EXISTS device_pool_available_idx
ON device_pool(status, cooldown_until, last_selected_at)
"""

CREATE_FAILURE_INDEX_SQL = """
CREATE INDEX IF NOT EXISTS device_pool_failure_idx
ON device_pool(last_failure_at)
"""

UPSERT_SQL = """
INSERT INTO device_pool (
    name,
    user_agent,
    cookie,
    cdid,
    install_id,
    device_id,
    aid,
    version_code,
    version_name,
    update_version_code,
    device_type,
    device_brand,
    rom_version,
    resolution,
    dpi,
    host_abi,
    os_version,
    os_api,
    status,
    failure_count,
    cooldown_until,
    last_failure_reason,
    last_failure_at,
    last_selected_at,
    last_success_at,
    source,
    created_at,
    updated_at
) VALUES (
    %(name)s,
    %(user_agent)s,
    %(cookie)s,
    %(cdid)s,
    %(install_id)s,
    %(device_id)s,
    %(aid)s,
    %(version_code)s,
    %(version_name)s,
    %(update_version_code)s,
    %(device_type)s,
    %(device_brand)s,
    %(rom_version)s,
    %(resolution)s,
    %(dpi)s,
    %(host_abi)s,
    %(os_version)s,
    %(os_api)s,
    'active',
    0,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    %(source)s,
    now(),
    now()
)
ON CONFLICT (device_id, install_id)
DO UPDATE SET
    name = EXCLUDED.name,
    user_agent = EXCLUDED.user_agent,
    cookie = EXCLUDED.cookie,
    cdid = EXCLUDED.cdid,
    aid = EXCLUDED.aid,
    version_code = EXCLUDED.version_code,
    version_name = EXCLUDED.version_name,
    update_version_code = EXCLUDED.update_version_code,
    device_type = EXCLUDED.device_type,
    device_brand = EXCLUDED.device_brand,
    rom_version = EXCLUDED.rom_version,
    resolution = EXCLUDED.resolution,
    dpi = EXCLUDED.dpi,
    host_abi = EXCLUDED.host_abi,
    os_version = EXCLUDED.os_version,
    os_api = EXCLUDED.os_api,
    status = 'active',
    cooldown_until = NULL,
    last_failure_reason = NULL,
    last_failure_at = NULL,
    source = EXCLUDED.source,
    updated_at = now()
RETURNING id
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="批量注册设备并补充 PostgreSQL 设备池")
    parser.add_argument("--db-url", default=os.getenv("DB_URL", ""), help="PostgreSQL 连接串，默认优先读取 DB_URL，其次读取仓库根目录 .env")
    parser.add_argument("--count", type=int, default=5, help="目标成功写库的设备数量，默认 5")
    parser.add_argument("--name-prefix", default="seed", help="设备名称前缀，默认 seed")
    parser.add_argument("--source", default="seed-script", help="写入 device_pool.source 的来源标识")
    parser.add_argument("--delay-min", type=float, default=3.0, help="两次注册之间的最小延迟秒数")
    parser.add_argument("--delay-max", type=float, default=5.0, help="两次注册之间的最大延迟秒数")
    parser.add_argument("--max-attempts", type=int, default=0, help="最大注册尝试次数；默认自动取 count 的 5 倍")
    parser.add_argument("--dry-run", action="store_true", help="仅生成映射与结果文件，不调用注册接口、不写数据库")
    return parser.parse_args()


def load_env_file(env_path: Path) -> Dict[str, str]:
    if not env_path.is_file():
        return {}

    result: Dict[str, str] = {}
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip().strip('"').strip("'")
    return result


def resolve_db_url(cli_value: str) -> str:
    explicit = (cli_value or "").strip()
    if explicit:
        return explicit

    env_value = (os.getenv("DB_URL") or "").strip()
    if env_value:
        return env_value

    env_map = load_env_file(REPO_ROOT / ".env")
    file_value = (env_map.get("DB_URL") or "").strip()
    if file_value:
        return file_value

    db = (env_map.get("POSTGRES_DB") or "").strip()
    user = (env_map.get("POSTGRES_USER") or "").strip()
    password = (env_map.get("POSTGRES_PASSWORD") or "").strip()
    port = (env_map.get("POSTGRES_PORT") or "").strip()
    host = (env_map.get("POSTGRES_HOST") or "127.0.0.1").strip() or "127.0.0.1"
    if db and user and password and port:
        return f"postgresql://{user}:{password}@{host}:{port}/{db}"
    return ""


def ensure_schema(conn: psycopg.Connection) -> None:
    with conn.cursor() as cur:
        cur.execute(CREATE_TABLE_SQL)
        cur.execute(CREATE_AVAILABLE_INDEX_SQL)
        cur.execute(CREATE_FAILURE_INDEX_SQL)


def build_device_name(prefix: str, batch_tag: str, index: int) -> str:
    normalized_prefix = (prefix or "seed").strip() or "seed"
    return f"{normalized_prefix}-{batch_tag}-{index:03d}"


def build_record(device_info: Dict[str, Any], device_name: str, source: str) -> Dict[str, Any]:
    xml_config = generate_xml_config(device_info)
    api_config = xml_config["fq"]["api"]
    device = api_config["device"]
    return {
        "name": device_name,
        "user_agent": api_config["user-agent"],
        "cookie": api_config["cookie"],
        "cdid": device.get("cdid"),
        "install_id": device.get("install-id"),
        "device_id": device.get("device-id"),
        "aid": device.get("aid"),
        "version_code": device.get("version-code"),
        "version_name": device.get("version-name"),
        "update_version_code": device.get("update-version-code"),
        "device_type": device.get("device-type"),
        "device_brand": device.get("device-brand"),
        "rom_version": device.get("rom-version"),
        "resolution": device.get("resolution"),
        "dpi": device.get("dpi"),
        "host_abi": device.get("host-abi"),
        "os_version": str(device_info.get("os_version") or ""),
        "os_api": str(device_info.get("os_api") or ""),
        "source": source,
    }


def upsert_record(conn: psycopg.Connection, record: Dict[str, Any]) -> int:
    with conn.cursor() as cur:
        cur.execute(UPSERT_SQL, record)
        row = cur.fetchone()
        return int(row[0]) if row else 0


def has_valid_device_identity(device_info: Dict[str, Any]) -> bool:
    if not isinstance(device_info, dict):
        return False

    def _valid(value: Any) -> bool:
        normalized = "" if value is None else str(value).strip()
        return normalized.isdigit() and int(normalized) > 0

    return _valid(device_info.get("device_id")) and _valid(device_info.get("install_id"))


def is_successful_registration(result: Dict[str, Any], device_info: Dict[str, Any]) -> bool:
    response = result.get("response") if isinstance(result, dict) else None
    if not isinstance(response, dict) or not response.get("success"):
        return False
    if response.get("valid_device_identity") is False:
        return False
    return has_valid_device_identity(device_info)


def register_devices(args: argparse.Namespace) -> None:
    os.chdir(REPO_ROOT)

    count = max(1, int(args.count or 1))
    delay_min = max(0.0, float(args.delay_min))
    delay_max = max(delay_min, float(args.delay_max))
    max_attempts = count if args.dry_run else max(count, int(args.max_attempts or (count * 5)))
    batch_tag = datetime.now().strftime("%Y%m%d_%H%M%S")

    conn: psycopg.Connection | None = None
    if not args.dry_run:
        db_url = resolve_db_url(args.db_url)
        if not db_url:
            raise SystemExit("缺少 --db-url / DB_URL / .env 中的 PostgreSQL 配置")
        conn = psycopg.connect(db_url)
        conn.autocommit = True
        ensure_schema(conn)

    client = None if args.dry_run else EnhancedDeviceRegisterClient()
    results: List[Dict[str, Any]] = []
    xml_configs: List[Dict[str, Any]] = []
    success_count = 0
    attempt_count = 0

    print(
        f"开始补池: target_count={count}, max_attempts={max_attempts}, dry_run={args.dry_run}, source={args.source}"
    )
    if not args.dry_run:
        print("数据库 schema 已确认")

    try:
        while attempt_count < max_attempts and (args.dry_run or success_count < count):
            attempt_count += 1
            device_name = build_device_name(args.name_prefix, batch_tag, attempt_count)
            device_info = ImprovedRandomDeviceGenerator.generate_random_device(
                use_real_algorithm=True,
                use_real_brand_model=True,
            )

            progress_index = attempt_count if args.dry_run else success_count + 1
            print(
                f"\n--- 设备 {progress_index}/{count}: {device_name} "
                f"(attempt {attempt_count}/{max_attempts}) ---"
            )

            if args.dry_run:
                record = build_record(device_info, device_name, args.source)
                xml_config = generate_xml_config(device_info)
                xml_configs.append(xml_config)
                results.append(
                    {
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                        "device_name": device_name,
                        "attempt_index": attempt_count,
                        "dry_run": True,
                        "db_record": record,
                        "request": {"device_info": device_info},
                        "response": {"success": False, "status_code": None, "content": "dry-run"},
                    }
                )
                print("dry-run: 已生成设备映射，未调用注册接口，未写数据库")
            else:
                assert client is not None
                result = client.register_device(device_info)
                result["device_name"] = device_name
                result["attempt_index"] = attempt_count
                results.append(result)

                success = is_successful_registration(result, device_info)
                if not success:
                    print(
                        "注册失败或未拿到有效 install_id/device_id，跳过数据库写入 "
                        f"(success={success_count}/{count}, attempt={attempt_count}/{max_attempts})"
                    )
                else:
                    xml_config = generate_xml_config(device_info)
                    xml_configs.append(xml_config)
                    record = build_record(device_info, device_name, args.source)
                    row_id = upsert_record(conn, record)
                    result["db_record"] = record
                    result["db_row_id"] = row_id
                    success_count += 1
                    print(
                        f"已写入设备池: row_id={row_id}, device_id={record['device_id']}, install_id={record['install_id']}"
                    )
                    print(f"当前进度: success={success_count}/{count}, attempt={attempt_count}/{max_attempts}")

            should_continue = (
                attempt_count < max_attempts and (args.dry_run and attempt_count < count or (not args.dry_run and success_count < count))
            )
            if should_continue and delay_max > 0:
                delay = random.uniform(delay_min, delay_max)
                print(f"等待 {delay:.1f} 秒继续...")
                time.sleep(delay)
    finally:
        if conn is not None:
            conn.close()

    full_file, xml_file = save_results(results, xml_configs, batch_tag)
    successful = sum(
        1 for item in results if is_successful_registration(item, ((item.get("request") or {}).get("device_info") or {}))
    )
    print("\n补池完成")
    print(f"结果文件: {full_file}")
    print(f"YAML 文件: {xml_file}")
    print(f"成功写库数量: {successful}")
    print(f"总尝试次数: {len(results)}")
    if not args.dry_run and successful < count:
        print(f"未达到目标成功数量: target={count}, actual={successful}, max_attempts={max_attempts}")


def main() -> None:
    args = parse_args()
    register_devices(args)


if __name__ == "__main__":
    main()