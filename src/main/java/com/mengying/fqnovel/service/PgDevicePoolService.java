package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQApiDeviceProfiles;
import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.config.FQCachePostgresConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * PostgreSQL 设备池：负责 schema、可用设备查询、设备选择记录和风险冷却落库。
 */
@Service
@Conditional(FQCachePostgresConfig.DbUrlPresentCondition.class)
public class PgDevicePoolService {

    private static final Logger log = LoggerFactory.getLogger(PgDevicePoolService.class);

    private static final String CREATE_TABLE_SQL = """
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
        """;

    private static final String CREATE_AVAILABLE_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS device_pool_available_idx
        ON device_pool(status, cooldown_until, last_selected_at)
        """;

    private static final String CREATE_FAILURE_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS device_pool_failure_idx
        ON device_pool(last_failure_at)
        """;

    private static final String COUNT_ALL_SQL = "SELECT COUNT(*) FROM device_pool";

    private static final String COUNT_AVAILABLE_SQL = """
        SELECT COUNT(*)
        FROM device_pool
        WHERE status = 'active'
          AND (cooldown_until IS NULL OR cooldown_until <= now())
          AND NULLIF(BTRIM(device_id), '') IS NOT NULL
          AND NULLIF(BTRIM(install_id), '') IS NOT NULL
          AND device_id <> '0'
          AND install_id <> '0'
        """;

    private static final String LIST_AVAILABLE_SQL = """
        SELECT name, user_agent, cookie,
               cdid, install_id, device_id, aid,
               version_code, version_name, update_version_code,
               device_type, device_brand, rom_version,
               resolution, dpi, host_abi, os_version, os_api
        FROM device_pool
        WHERE status = 'active'
          AND (cooldown_until IS NULL OR cooldown_until <= now())
          AND NULLIF(BTRIM(device_id), '') IS NOT NULL
          AND NULLIF(BTRIM(install_id), '') IS NOT NULL
          AND device_id <> '0'
          AND install_id <> '0'
        ORDER BY last_selected_at NULLS FIRST, updated_at ASC, id ASC
        LIMIT ?
        """;

    private static final String MARK_SELECTED_SQL = """
        UPDATE device_pool
        SET last_selected_at = now(),
            updated_at = now()
        WHERE device_id = ? AND install_id = ?
        """;

    private static final String MARK_RISK_COOLDOWN_SQL = """
        UPDATE device_pool
        SET failure_count = failure_count + 1,
            cooldown_until = ?,
            last_failure_reason = ?,
            last_failure_at = now(),
            updated_at = now()
        WHERE device_id = ? AND install_id = ?
        """;

    private static final String MARK_SUCCESS_SQL = """
        UPDATE device_pool
        SET last_success_at = now(),
            updated_at = now()
        WHERE device_id = ? AND install_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public PgDevicePoolService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initSchema() {
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            jdbcTemplate.execute(CREATE_AVAILABLE_INDEX_SQL);
            jdbcTemplate.execute(CREATE_FAILURE_INDEX_SQL);
            log.info("设备池表已就绪");
        } catch (Exception e) {
            log.error("初始化 PostgreSQL 设备池表失败", e);
            throw e;
        }
    }

    public int countAllDevices() {
        return countBySql(COUNT_ALL_SQL);
    }

    public int countAvailableDevices() {
        return countBySql(COUNT_AVAILABLE_SQL);
    }

    public List<FQApiProperties.DeviceProfile> listAvailableProfiles(int limit) {
        int resolvedLimit = Math.max(1, limit);
        try {
            return jdbcTemplate.query(
                LIST_AVAILABLE_SQL,
                ps -> ps.setInt(1, resolvedLimit),
                (rs, rowNum) -> mapProfile(rs)
            );
        } catch (Exception e) {
            log.warn("读取 PostgreSQL 设备池失败", e);
            return List.of();
        }
    }

    public void markSelected(FQApiProperties.DeviceProfile profile) {
        if (profile == null || profile.getDevice() == null) {
            return;
        }
        String deviceId = profile.getDevice().getDeviceId();
        String installId = profile.getDevice().getInstallId();
        if (!hasIdentity(deviceId, installId)) {
            return;
        }
        try {
            jdbcTemplate.update(MARK_SELECTED_SQL, deviceId, installId);
        } catch (Exception e) {
            log.warn("更新设备最近选择时间失败 - deviceId={}, installId={}", deviceId, installId, e);
        }
    }

    public void markSuccess(FQApiProperties.RuntimeProfile runtimeProfile) {
        FQApiProperties.Device device = runtimeProfile == null ? null : runtimeProfile.getDeviceUnsafe();
        String deviceId = device == null ? null : device.getDeviceId();
        String installId = device == null ? null : device.getInstallId();
        if (!hasIdentity(deviceId, installId)) {
            return;
        }
        try {
            jdbcTemplate.update(MARK_SUCCESS_SQL, deviceId, installId);
        } catch (Exception e) {
            log.debug("更新设备成功时间失败 - deviceId={}, installId={}", deviceId, installId, e);
        }
    }

    public boolean markRiskCooldown(FQApiProperties.RuntimeProfile runtimeProfile, String reason, long cooldownMs) {
        FQApiProperties.Device device = runtimeProfile == null ? null : runtimeProfile.getDeviceUnsafe();
        String deviceId = device == null ? null : device.getDeviceId();
        String installId = device == null ? null : device.getInstallId();
        if (!hasIdentity(deviceId, installId)) {
            return false;
        }

        long safeCooldownMs = Math.max(0L, cooldownMs);
        Timestamp cooldownUntil = Timestamp.from(Instant.ofEpochMilli(System.currentTimeMillis() + safeCooldownMs));
        try {
            int updated = jdbcTemplate.update(MARK_RISK_COOLDOWN_SQL, cooldownUntil, normalizeReason(reason), deviceId, installId);
            return updated > 0;
        } catch (Exception e) {
            log.warn("写入设备风险冷却失败 - deviceId={}, installId={}, reason={}", deviceId, installId, reason, e);
            return false;
        }
    }

    private int countBySql(String sql) {
        try {
            Integer result = jdbcTemplate.queryForObject(sql, Integer.class);
            return result == null ? 0 : result;
        } catch (Exception e) {
            log.warn("读取设备池数量失败 - sql={}", sql, e);
            return 0;
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "RISK_CONTROL";
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    private static boolean hasIdentity(String deviceId, String installId) {
        return isValidIdentityValue(deviceId) && isValidIdentityValue(installId);
    }

    private static boolean isValidIdentityValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return !normalized.isEmpty() && !"0".equals(normalized);
    }

    private static FQApiProperties.DeviceProfile mapProfile(ResultSet rs) throws SQLException {
        FQApiProperties.Device device = new FQApiProperties.Device();
        device.setCdid(rs.getString("cdid"));
        device.setInstallId(rs.getString("install_id"));
        device.setDeviceId(rs.getString("device_id"));
        device.setAid(rs.getString("aid"));
        device.setVersionCode(rs.getString("version_code"));
        device.setVersionName(rs.getString("version_name"));
        device.setUpdateVersionCode(rs.getString("update_version_code"));
        device.setDeviceType(rs.getString("device_type"));
        device.setDeviceBrand(rs.getString("device_brand"));
        device.setRomVersion(rs.getString("rom_version"));
        device.setResolution(rs.getString("resolution"));
        device.setDpi(rs.getString("dpi"));
        device.setHostAbi(rs.getString("host_abi"));
        device.setOsVersion(rs.getString("os_version"));
        device.setOsApi(rs.getString("os_api"));

        FQApiProperties.DeviceProfile profile = new FQApiProperties.DeviceProfile();
        profile.setName(rs.getString("name"));
        profile.setUserAgent(rs.getString("user_agent"));
        profile.setCookie(rs.getString("cookie"));
        profile.setDevice(FQApiDeviceProfiles.copyDevice(device));
        return profile;
    }
}