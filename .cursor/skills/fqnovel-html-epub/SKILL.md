---
name: fqnovel-html-epub
description: Export and verify FQNovel TXT/HTML EPUB output. Use when testing `fqnovel_epub`, `useHtmlStyle`, whole-book exports, batch fallback behavior, or validating generated chapter XHTML.
---

# FQNovel HTML EPUB Workflow

用于这个仓库里番茄小说 HTML/TXT EPUB 导出与验证的固定流程。

## 何时使用

- 用户要测试 `fqnovel_epub`
- 用户提到 `useHtmlStyle`
- 用户要跑整本 EPUB
- 用户要验证生成的 EPUB 章节 XHTML
- 用户要定位某一段章节批量请求失败

## 执行顺序

### 1. 先确认服务端状态

- 优先复用用户已经跑着的 Java 服务
- 如果要验证最新 Java 改动，起一份独立端口服务，再把 `FQNOVEL_BASE_URL` 指过去
- MCP 侧先执行：

```bash
cd fqnovel-mcp-ts
npm run build
```

### 2. 先做小范围 smoke

先跑 10-20 章的小范围 EPUB，确认：

- `contentStyle = html`
- 章节数正确
- XHTML 中存在 `class="blk"`
- XHTML 中存在 `class="book-title"`
- XHTML 中不存在原始 `<blk>` 标签

### 3. 再跑整本

整本 HTML EPUB 直接使用：

- `fqnovel_epub`
- `useHtmlStyle=true`
- 合理的 `outputPath`

如果要看长任务进度，设置：

```bash
FQNOVEL_PROGRESS=1
```

然后观察批次日志：

- `batch X/Y start`
- `batch X/Y done`
- `split batch ...` 表示某个批次空响应后自动拆小继续抓
- `fallback single chapter ...` 表示已经回退到单章接口继续抓取

## 故障处理

### 批量接口空响应 / 风控抖动

优先做这些判断：

1. 是不是只在某个章节段失败
2. 是不是重试后能通过
3. 拆小批次后是否能通过
4. 单章接口是否能拿到正文

当前项目约定：

- 批量 HTML EPUB 导出先走批量接口
- 批量失败时自动拆小批次
- 拆到单章后仍失败，才回退单章接口

### 用户抱怨黑窗没日志

不要让用户盯前台黑窗。

优先：

- 后台运行
- 日志重定向到文件
- 打开 `FQNOVEL_PROGRESS=1`

## 验证标准

至少抽样检查：

- 第一章
- 中间章节
- 最后一章

检查点：

- 章节 XHTML 数量是否符合预期
- `hasBlkClass = true`
- `hasBookTitleClass = true`
- `hasRawBlkTag = false`
- 章节内容不是空白页

## 产物清理

任务结束后：

- 删除 `.tmp-*`
- 删除临时导出文件
- 保留真正需要提交的 README / 技能 / 源码改动
- 提交前再次确认没有遗留后台进程和测试垃圾