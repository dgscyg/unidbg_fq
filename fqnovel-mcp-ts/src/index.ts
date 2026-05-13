import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { FqNovelClient } from "./client.js";
import { loadConfig } from "./config.js";
import { buildEpub, buildTocSummary, downloadBook } from "./download.js";
import { createToolErrorResult } from "./errors.js";
import { ensureStructuredContent, jsonText, numericIdSchema } from "./utils.js";

const server = new McpServer({
  name: "fqnovel-mcp-ts",
  version: "1.0.0",
});

const appConfig = loadConfig();
const client = new FqNovelClient(appConfig);

function createJsonResult(payload: Record<string, unknown>, summary?: string): CallToolResult {
  const text = summary ? `${summary}\n\n${jsonText(payload)}` : jsonText(payload);
  return {
    content: [{ type: "text", text }],
    structuredContent: payload,
  };
}

function createTextResult(text: string, payload?: Record<string, unknown>): CallToolResult {
  return {
    content: [{ type: "text", text }],
    structuredContent: payload,
  };
}

function registerTool(
  name: string,
  description: string,
  inputSchema: z.ZodTypeAny,
  handler: (args: any) => Promise<CallToolResult>,
) {
  server.registerTool(name, { description, inputSchema: inputSchema as any }, async (args: any) => {
    try {
      return await handler(args);
    } catch (error) {
      return createToolErrorResult(error);
    }
  });
}

registerTool(
  "fqnovel_search",
  "搜索番茄小说书籍，封装上游 GET /search 接口。结果中包含封面链接 coverUrl。",
  z.object({
    key: z.string().trim().min(1, "key 不能为空").max(100, "key 过长"),
    page: z.number().int().min(1).default(1),
    size: z.number().int().min(1).max(50).default(20),
    tabType: z.number().int().min(1).max(20).default(3),
    searchId: z.string().trim().min(1).optional(),
  }),
  async (args) => {
    const response = await client.searchBooks(args);
    const books = response.books ?? [];
    const payload = {
      request: args,
      response: {
        ...response,
        books,
      },
    };

    return createJsonResult(payload, `搜索完成，共返回 ${books.length} 本书。`);
  },
);

registerTool(
  "fqnovel_toc",
  "获取书籍目录，封装上游 GET /toc/{bookId} 接口。",
  z.object({
    bookId: numericIdSchema("bookId"),
  }),
  async (args) => {
    const response = await client.getToc(args.bookId);
    const summary = buildTocSummary(args.bookId, response);
    const payload = {
      request: args,
      response,
      summary,
    };

    return createJsonResult(payload, `目录获取完成，共 ${summary.totalChapters} 章。`);
  },
);

registerTool(
  "fqnovel_book",
  "获取书籍详情，封装上游 GET /book/{bookId} 接口。结果中包含封面链接 coverUrl。",
  z.object({
    bookId: numericIdSchema("bookId"),
  }),
  async (args) => {
    const response = await client.getBook(args.bookId);
    const payload = {
      request: args,
      response,
    };

    return createJsonResult(payload, `书籍详情获取完成：${response.bookName ?? args.bookId}`);
  },
);

registerTool(
  "fqnovel_chapter",
  "获取单章正文，封装上游 GET /chapter/{bookId}/{chapterId} 接口，支持切换 txt 纯文本或原始 HTML 样式。",
  z.object({
    bookId: numericIdSchema("bookId"),
    chapterId: numericIdSchema("chapterId"),
    includeRawContent: z.boolean().default(false),
    useHtmlStyle: z.boolean().default(false),
  }),
  async (args) => {
    const response = await client.getChapter(
      args.bookId,
      args.chapterId,
      args.includeRawContent,
      args.useHtmlStyle,
    );
    const showRawContent = args.includeRawContent || args.useHtmlStyle;
    const textBody = args.useHtmlStyle
      ? response.rawContent?.trim() || response.txtContent?.trim() || ""
      : response.txtContent?.trim() || response.rawContent?.trim() || "";
    const payload = {
      request: args,
      response: {
        ...response,
        contentStyle: args.useHtmlStyle ? "html" : "txt",
        rawContent: showRawContent ? response.rawContent : undefined,
      },
    };
    const title = response.title?.trim() || args.chapterId;
    const text = [`第${response.chapterIndex ?? "?"}章 ${title}`, "", textBody].join("\n");

    return createTextResult(text, ensureStructuredContent(payload));
  },
);

registerTool(
  "fqnovel_download",
  "批量下载章节。只走上游批量章节接口，不逐章抓取，降低风控概率。支持切换 txt 纯文本或原始 HTML 样式，可输出 txt/json。",
  z.object({
    bookId: numericIdSchema("bookId"),
    startChapter: z.number().int().min(1).optional(),
    endChapter: z.number().int().min(1).optional(),
    concurrency: z.number().int().min(1).max(30).optional(),
    includeRawContent: z.boolean().default(false),
    useHtmlStyle: z.boolean().default(false),
    aggregateText: z.boolean().default(true),
    outputPath: z.string().trim().min(1).optional(),
    outputFormat: z.enum(["txt", "json"]).optional(),
  }),
  async (args) => {
    const download = await downloadBook(client, appConfig, args);
    const payload = {
      request: args,
      download,
    };

    if (download.output) {
      return createTextResult(
        `批量下载完成：${download.book.bookName ?? download.book.bookId}\n章节范围：${download.chapterRange.start}-${download.chapterRange.end}\n正文样式：${download.contentStyle}\n批量请求数：${download.batchInfo.requestCount}\n输出文件：${download.output.path}`,
        ensureStructuredContent(payload),
      );
    }

    if (download.aggregateText) {
      return createTextResult(download.aggregateText, ensureStructuredContent(payload));
    }

    return createJsonResult(payload, `批量下载完成，共 ${download.chapters.length} 章。`);
  },
);

registerTool(
  "fqnovel_epub",
  "整本小说 EPUB 下载。内部固定按 30 章一批串行拉取正文，支持本地断点续传；成功后默认清理断点缓存，传 keepResumeCache=true 时保留。",
  z.object({
    bookId: numericIdSchema("bookId"),
    startChapter: z.number().int().min(1).optional(),
    endChapter: z.number().int().min(1).optional(),
    concurrency: z.number().int().min(1).max(30).optional(),
    useHtmlStyle: z.boolean().default(false),
    keepResumeCache: z.boolean().default(false),
    outputPath: z.string().trim().min(1).optional(),
  }),
  async (args) => {
    const epub = await buildEpub(client, appConfig, args);
    const payload = {
      request: args,
      epub,
    };

    return createTextResult(
      `EPUB 已生成：${epub.book.bookName ?? epub.book.bookId}\n章节范围：${epub.chapterRange.start}-${epub.chapterRange.end}\n正文样式：${epub.contentStyle}\n实际请求批次：${epub.batchInfo.requestCount}/${epub.batchInfo.totalBatches ?? epub.batchInfo.requestCount}\n缓存命中批次：${epub.batchInfo.cacheHitBatches ?? 0}\n缓存命中章节：${epub.batchInfo.cachedChapters ?? 0}\n断点缓存：${epub.resumeCacheKept ? "保留" : "已清理"}\n封面嵌入：${epub.coverEmbedded ? "yes" : "no"}\n断点目录：${epub.resumeDir}\n输出文件：${epub.output.path}`,
      ensureStructuredContent(payload),
    );
  },
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((error) => {
  const message = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  process.stderr.write(`${message}\n`);
  process.exit(1);
});