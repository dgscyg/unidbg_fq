# 编译步骤

## 环境要求

- JDK 25
- Maven 3.9+
- Python 3.10+（仅在运行补池脚本时需要）

Windows 当前建议环境示例：

```powershell
$env:JAVA_HOME="F:\tmp\jdk25\jdk-25.0.3"
$env:M2_HOME="F:\tmp\maven\apache-maven-3.9.15"
$env:Path="$env:JAVA_HOME\bin;$env:M2_HOME\bin;$env:Path"
```

## 本地编译

```bash
mvn -DskipTests package
```

编译完成后，Jar 位于 `target/fqnovel.jar`。

## 运行示例

```bash
java -jar target/fqnovel.jar
```

## Docker Compose

首次使用：

```bash
copy .env.example .env
```

按需修改 `.env` 中的 PostgreSQL 账号、密码、端口和设备池相关参数，然后启动：

```bash
docker compose up -d --build
```

当前本地测试库示例：

- host: `127.0.0.1`
- port: `15432`
- db: `postgres`
- user: `postgres`
- password: `sssdragon`

当前编排内容：

- `postgres`: PostgreSQL 17
- `app`: 当前 Spring Boot 服务

关键点：

- 容器内使用 `DB_URL=postgresql://...@postgres:5432/...` 连接数据库
- 外置配置通过 `docker/app/application.yml` 挂载到 `/app/config/application.yml`
- 容器设置了 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/`，所以你可以直接改 `docker/app/application.yml` 而不用重打镜像

相关文件：

- `docker-compose.yml`
- `.env.example`
- `docker/app/application.yml`

## PostgreSQL 设备池行为

现在设备池不再放在 `src/main/resources/application.yml` 里硬编码。

服务启动时会：

- 优先从 PostgreSQL `device_pool` 表选择可用设备
- 若当前没有可用设备，服务允许降级启动
- 降级启动后，请求阶段会明确返回“当前没有可用设备，请先运行补池脚本写入 PostgreSQL 设备池”
- 一旦后续补池脚本写入了可用设备，服务会在后续请求时自动激活设备，不需要重启进程

风险失败（如 `ILLEGAL_ACCESS` / forbidden / risk-control）会触发：

- 当前设备写入 PostgreSQL 冷却
- `cooldown_until = now + 12h`
- 尝试切换到下一个可用设备
- 切换后刷新 `registerkey`

其他可重试失败仍然保留原有短期切换 / 自愈逻辑，不进入 12 小时冷却。

## 手动补池脚本

补池脚本：`utils/fq_device_pool_seed.py`

它会复用现有 `utils/fq_device_register.py` 的设备生成与注册调用链，注册成功后把设备 upsert 到 PostgreSQL `device_pool` 表。

安装依赖：

```bash
python -m pip install -r utils/requirements.txt
```

脚本读取数据库连接信息的优先级：

1. `--db-url`
2. 当前进程环境变量 `DB_URL`
3. 仓库根目录 `.env` 中的 `DB_URL`
4. 仓库根目录 `.env` 中的 `POSTGRES_HOST/POSTGRES_PORT/POSTGRES_DB/POSTGRES_USER/POSTGRES_PASSWORD` 自动拼装

最小示例：

```bash
python utils/fq_device_pool_seed.py --db-url "postgresql://postgres:sssdragon@127.0.0.1:15432/postgres" --count 5 --name-prefix seed
```

如果你测试的是已经存在的本地 PostgreSQL，而不是 compose 拉起的那一套，当前已确认可连通的组合是：

- DSN: `postgresql://postgres:sssdragon@127.0.0.1:15432/postgres`
- 如果你后续改了本地实例用户名 / 数据库名，直接改 `--db-url` 即可

常用参数：

- `--db-url`: PostgreSQL 连接串；默认读取 `DB_URL`
- `--count`: 生成并注册的设备数量
- `--name-prefix`: 写入 `device_pool.name` 的前缀
- `--source`: 写入 `device_pool.source` 的来源标识
- `--delay-min` / `--delay-max`: 两次注册之间的延迟秒数
- `--dry-run`: 只生成映射和结果文件，不调用注册接口、不写数据库

脚本会继续把原始结果和 YAML 映射落到 `results/`。

## TypeScript MCP 子项目

仓库内包含独立的 MCP 服务子项目：`fqnovel-mcp-ts/`

它复用当前 Java HTTP 服务，提供：

- 搜索 / 目录 / 详情 / 单章
- 批量下载
- EPUB 导出
- `useHtmlStyle=true` 的原始 HTML 样式正文与 EPUB 导出

快速开始：

```bash
cd fqnovel-mcp-ts
npm install
npm run build
npm start
```

详细参数、工具说明和 HTML EPUB 用法见：`fqnovel-mcp-ts/README.md`

## 验证顺序

1. `mvn -DskipTests package`
2. `docker compose up -d --build`
3. 空设备池下访问接口，确认返回“当前没有可用设备”
4. 运行补池脚本写入设备
5. 再次访问接口，确认服务自动激活并可正常请求上游
6. 人工制造风险失败，确认当前设备进入 12 小时冷却并切到下一个可用设备

## 注意

最后一个 java8 版本是 `1.7.6`

## 免责声明

**本项目仅供学习交流使用，使用时请遵守相关法律法规。用户需自行承担由此引发的任何法律责任和风险。程序的作者及项目贡献者不对因使用本程序所造成的任何损失、损害或法律后果负责！**