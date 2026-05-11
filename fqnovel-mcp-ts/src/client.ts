import { FqNovelApiError } from "./errors.js";
import type {
  ApiEnvelope,
  BatchChaptersResponse,
  BookInfo,
  ChapterInfo,
  DirectoryResponse,
  SearchResponse,
} from "./types.js";
import type { AppConfig } from "./config.js";

interface SearchParams {
  key: string;
  page: number;
  size: number;
  tabType: number;
  searchId?: string;
}

type QueryValue = string | number | boolean | undefined;

interface RequestOptions {
  method?: "GET" | "POST";
  query?: Record<string, QueryValue>;
  body?: unknown;
}

function buildUrl(baseUrl: string, pathName: string, query?: Record<string, QueryValue>): URL {
  const base = `${baseUrl}/`;
  const url = new URL(pathName.replace(/^\//, ""), base);

  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) {
        url.searchParams.set(key, String(value));
      }
    }
  }

  return url;
}

function normalizeDirectoryResponse(raw: any): DirectoryResponse {
  const sourceItems = Array.isArray(raw?.itemDataList)
    ? raw.itemDataList
    : Array.isArray(raw?.item_data_list)
      ? raw.item_data_list
      : [];

  return {
    serialCount:
      typeof raw?.serialCount === "number"
        ? raw.serialCount
        : typeof raw?.serial_count === "number"
          ? raw.serial_count
          : undefined,
    bookInfo: raw?.bookInfo ?? raw?.book_info,
    itemDataList: sourceItems
      .map((item: any) => ({
        itemId: item?.itemId ?? item?.item_id ?? "",
        title: item?.title,
        chapterIndex: item?.chapterIndex ?? item?.chapter_index,
        isLatest: item?.isLatest ?? item?.is_latest,
        firstPassTime: item?.firstPassTime ?? item?.first_pass_time,
        firstPassTimeStr: item?.firstPassTimeStr ?? item?.first_pass_time_str,
        sortOrder: item?.sortOrder ?? item?.sort_order,
        isFree: item?.isFree ?? item?.is_free,
      }))
      .filter((item: { itemId: string }) => item.itemId),
  };
}

export class FqNovelClient {
  constructor(private readonly config: AppConfig) {}

  async searchBooks(params: SearchParams): Promise<SearchResponse> {
    return this.request<SearchResponse>("/search", {
      query: {
        key: params.key,
        page: params.page,
        size: params.size,
        tabType: params.tabType,
        searchId: params.searchId,
      },
    });
  }

  async getToc(bookId: string): Promise<DirectoryResponse> {
    const response = await this.request<any>(`/toc/${encodeURIComponent(bookId)}`);
    return normalizeDirectoryResponse(response);
  }

  async getBook(bookId: string): Promise<BookInfo> {
    return this.request<BookInfo>(`/book/${encodeURIComponent(bookId)}`);
  }

  async getChapter(bookId: string, chapterId: string): Promise<ChapterInfo> {
    return this.request<ChapterInfo>(
      `/chapter/${encodeURIComponent(bookId)}/${encodeURIComponent(chapterId)}`,
    );
  }

  async getBatchChapters(
    bookId: string,
    chapterIds: string[],
    includeRawContent: boolean,
  ): Promise<BatchChaptersResponse> {
    return this.request<BatchChaptersResponse>("/chapters/batch", {
      method: "POST",
      body: {
        bookId,
        chapterIds,
        includeRawContent,
      },
    });
  }

  private async request<T>(pathName: string, options: RequestOptions = {}): Promise<T> {
    const url = buildUrl(this.config.baseUrl, pathName, options.query);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.timeoutMs);

    let responseText = "";

    try {
      const response = await fetch(url, {
        method: options.method ?? "GET",
        headers: {
          accept: "application/json",
          ...(options.body !== undefined ? { "content-type": "application/json" } : {}),
        },
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
        signal: controller.signal,
      });

      responseText = await response.text();

      if (!response.ok) {
        throw new FqNovelApiError(`上游 HTTP 请求失败: ${response.status}`, {
          endpoint: url.pathname,
          status: response.status,
        });
      }

      let envelope: ApiEnvelope<T>;
      try {
        envelope = JSON.parse(responseText) as ApiEnvelope<T>;
      } catch {
        throw new FqNovelApiError("上游返回非 JSON 数据", {
          endpoint: url.pathname,
          status: response.status,
        });
      }

      if (typeof envelope?.code !== "number") {
        throw new FqNovelApiError("上游响应缺少 code 字段", {
          endpoint: url.pathname,
          status: response.status,
        });
      }

      if (envelope.code !== 0) {
        throw new FqNovelApiError(envelope.message || "上游返回错误", {
          endpoint: url.pathname,
          status: response.status,
          code: envelope.code,
        });
      }

      if (envelope.data === null) {
        throw new FqNovelApiError("上游返回空 data", {
          endpoint: url.pathname,
          status: response.status,
          code: envelope.code,
        });
      }

      return envelope.data;
    } catch (error) {
      if (error instanceof FqNovelApiError) {
        throw error;
      }

      if (error instanceof Error && error.name === "AbortError") {
        throw new FqNovelApiError(`请求超时（${this.config.timeoutMs}ms）`, {
          endpoint: pathName,
        });
      }

      const message = error instanceof Error ? error.message : "未知请求错误";
      throw new FqNovelApiError(message, {
        endpoint: pathName,
      });
    } finally {
      clearTimeout(timeout);
    }
  }
}