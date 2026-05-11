# 编译步骤

## 环境要求

- JDK 25
- Maven 3.9.12

## 本地编译

```bash
mvn -DskipTests package
```

编译完成后，Jar 位于 `target/fqnovel.jar`。

## 运行示例

```bash
java -jar target/fqnovel.jar
```

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

## docker
本地缓存版
```bash
docker run -d --name fqnovel --restart=unless-stopped -p 9999:9999 gxmandppx/unidbg-fq:latest
```
postgresql缓存版
```bash
docker run -d --name fqnovel --restart=unless-stopped -p 9999:9999 -e DB_URL='postgresql://user:password@ip:端口/db' gxmandppx/unidbg-fq:latest
```

## 注意

最后一个java8版本是1.7.6版本

## 免责声明

**本项目仅供学习交流使用，使用时请遵守相关法律法规。用户需自行承担由此引发的任何法律责任和风险。程序的作者及项目贡献者不对因使用本程序所造成的任何损失、损害或法律后果负责！**
