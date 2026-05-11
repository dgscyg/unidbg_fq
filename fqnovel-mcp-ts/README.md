# fqnovel-mcp-ts

一个独立的 TypeScript MCP 服务，复用当前仓库里的 Java HTTP 服务，向 MCP 客户端提供番茄小说能力。

## 依赖前提

先启动当前仓库里的 Java 服务，默认地址：`http://127.0.0.1:9999`

已封装的上游接口：

- `GET /search`
- `GET /toc/{bookId}`
- `GET /book/{bookId}`
- `GET /chapter/{bookId}/{chapterId}`
- `POST /chapters/batch`

## 环境变量

- `FQNOVEL_BASE_URL`：上游服务地址，默认 `http://127.0.0.1:9999`
- `FQNOVEL_TIMEOUT_MS`：单次请求超时，默认 `30000`
- `FQNOVEL_DOWNLOAD_CONCURRENCY`：批量下载默认并发，默认 `5`
- `FQNOVEL_OUTPUT_DIR`：下载输出相对路径的基准目录，默认当前工作目录

## 启动

```bash
npm install
npm run build
npm start
```

开发模式：

```bash
npm run dev
```

## 提供的工具

- `fqnovel_search`：搜索，返回结构里包含 `coverUrl`
- `fqnovel_toc`：目录
- `fqnovel_book`：详情，返回结构里包含 `coverUrl`
- `fqnovel_chapter`：单章正文
- `fqnovel_download`：批量章节下载，只走 `POST /chapters/batch`
- `fqnovel_epub`：整本小说 EPUB 下载，内部按批量章节接口分批拉取

## 下载说明

### 批量章节下载

`fqnovel_download` 会：

1. 获取书籍详情
2. 获取目录
3. 按 30 章一批调用 `POST /chapters/batch`
4. 按需聚合文本
5. 可选写入 `txt/json`

`startChapter` / `endChapter` 为 **1-based**、闭区间。

### EPUB 下载

`fqnovel_epub` 会：

1. 获取书籍详情和封面链接
2. 下载封面图片并统一转成 JPEG 后嵌入 EPUB
3. 获取目录
4. 按批量章节接口分批拉取全书正文
5. 生成标准 `.epub` zip 包
6. 写入封面、元数据、章节目录

默认会把 `.epub` 写到 `FQNOVEL_OUTPUT_DIR` 或当前工作目录。