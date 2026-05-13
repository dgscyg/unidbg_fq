export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T | null;
  serverTime?: number;
}

export interface SearchBookItem {
  bookId: string;
  bookName: string;
  author?: string;
  description?: string;
  coverUrl?: string;
  lastChapterTitle?: string;
  category?: string;
  wordCount?: number;
}

export interface SearchResponse {
  books?: SearchBookItem[];
  total?: number;
  hasMore?: boolean;
  searchId?: string;
}

export interface DirectoryItem {
  itemId: string;
  title?: string;
  chapterIndex?: number;
  isLatest?: boolean;
  firstPassTime?: number;
  firstPassTimeStr?: string;
  sortOrder?: number;
  isFree?: boolean;
}

export interface DirectoryResponse {
  serialCount?: number;
  itemDataList?: DirectoryItem[];
  bookInfo?: BookInfo;
}

export interface BookInfo {
  bookId?: string;
  bookName?: string;
  author?: string;
  description?: string;
  coverUrl?: string;
  totalChapters?: number;
  wordNumber?: number;
  lastChapterTitle?: string;
  category?: string;
  status?: number;
}

export interface ChapterInfo {
  chapterId?: string;
  bookId?: string;
  authorName?: string;
  title?: string;
  rawContent?: string;
  chapterIndex?: number;
  wordCount?: number;
  updateTime?: number;
  prevChapterId?: string;
  nextChapterId?: string;
  isFree?: boolean;
  txtContent?: string;
}

export interface BatchChaptersResponse {
  bookId?: string;
  requestedCount?: number;
  returnedCount?: number;
  partial?: boolean;
  missingChapterIds?: string[];
  chapters?: ChapterInfo[];
}

export interface TocChapter {
  index: number;
  chapterId: string;
  title: string;
  isFree?: boolean;
}

export interface DownloadChapter {
  index: number;
  chapterId: string;
  title: string;
  content: string;
  contentStyle: ContentStyle;
  rawContent?: string;
  wordCount?: number;
  nextChapterId?: string;
  prevChapterId?: string;
  isFree?: boolean;
}

export type ContentStyle = "txt" | "html";
export type OutputFormat = "txt" | "json";
export type BinaryOutputFormat = "epub";

export interface DownloadOutput {
  path: string;
  format: OutputFormat | BinaryOutputFormat;
  bytes: number;
}

export interface BatchRequestInfo {
  batchSize: number;
  requestCount: number;
  totalBatches?: number;
  cacheHitBatches?: number;
  cachedChapters?: number;
}

export interface DownloadResult {
  book: Required<Pick<BookInfo, "bookId">> & BookInfo;
  chapterRange: {
    start: number;
    end: number;
    totalSelected: number;
    totalAvailable: number;
  };
  contentStyle: ContentStyle;
  batchInfo: BatchRequestInfo;
  chapters: DownloadChapter[];
  aggregateText?: string;
  output?: DownloadOutput;
}

export interface EpubResult {
  book: Required<Pick<BookInfo, "bookId">> & BookInfo;
  chapterRange: {
    start: number;
    end: number;
    totalSelected: number;
    totalAvailable: number;
  };
  contentStyle: ContentStyle;
  batchInfo: BatchRequestInfo;
  output: DownloadOutput;
  resumeDir: string;
  resumeCacheKept: boolean;
  coverUrl?: string;
  coverEmbedded: boolean;
  coverError?: string;
}

export interface DownloadOptions {
  bookId: string;
  startChapter?: number;
  endChapter?: number;
  concurrency?: number;
  includeRawContent?: boolean;
  useHtmlStyle?: boolean;
  aggregateText?: boolean;
  outputPath?: string;
  outputFormat?: OutputFormat;
}

export interface EpubOptions {
  bookId: string;
  startChapter?: number;
  endChapter?: number;
  concurrency?: number;
  useHtmlStyle?: boolean;
  keepResumeCache?: boolean;
  outputPath?: string;
}