import { createHash } from "node:crypto";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, extname, isAbsolute, resolve } from "node:path";
import JSZip from "jszip";
import sharp from "sharp";
import heicConvert from "heic-convert";
import type { AppConfig } from "./config.js";
import { FqNovelClient } from "./client.js";
import { FqNovelApiError } from "./errors.js";
import type {
  BatchRequestInfo,
  BookInfo,
  ChapterInfo,
  ContentStyle,
  DownloadChapter,
  DownloadOptions,
  DownloadOutput,
  DownloadResult,
  EpubOptions,
  EpubResult,
  OutputFormat,
  TocChapter,
} from "./types.js";
import { clamp } from "./utils.js";

const MAX_BATCH_CHAPTERS_PER_REQUEST = 30;
const EPUB_BATCH_SIZE = MAX_BATCH_CHAPTERS_PER_REQUEST;
const EPUB_BATCH_DELAY_MS = 0;
const EPUB_BATCH_RETRY_MAX_ATTEMPTS = 3;
const EPUB_BATCH_RETRY_DELAY_MS = 65_000;
const EPUB_RESUME_VERSION = 1;

interface DownloadPreparation {
  book: Required<Pick<BookInfo, "bookId">> & BookInfo;
  toc: TocChapter[];
  selected: TocChapter[];
  chapterRange: DownloadResult["chapterRange"];
}

interface CoverAsset {
  fileName: string;
  mediaType: string;
  data: Buffer;
}

interface BatchFetchOptions {
  batchSize: number;
  delayMs: number;
  retryMaxAttempts: number;
  retryDelayMs: number;
}

interface EpubResumeMetadata {
  version: number;
  bookId: string;
  contentStyle: ContentStyle;
  includeRawContent: boolean;
  batchSize: number;
  totalBatches: number;
  chapterRange: DownloadResult["chapterRange"];
  selectedSignature: string;
}

interface EpubResumeBatchCache {
  version: number;
  bookId: string;
  batchIndex: number;
  batchSignature: string;
  chapters: DownloadChapter[];
}

function normalizeBookInfo(book: BookInfo, fallbackBookId: string): Required<Pick<BookInfo, "bookId">> & BookInfo {
  return {
    ...book,
    bookId: book.bookId || fallbackBookId,
  };
}

function normalizeTocChapters(bookId: string, directory: { itemDataList?: Array<{ itemId: string; title?: string; isFree?: boolean }> | undefined }): TocChapter[] {
  const items = directory.itemDataList ?? [];

  return items
    .filter((item) => item?.itemId)
    .map((item, index) => ({
      index: index + 1,
      chapterId: item.itemId,
      title: item.title?.trim() || `第${index + 1}章`,
      isFree: item.isFree,
    }));
}

function resolveChapterRange(totalAvailable: number, startChapter?: number, endChapter?: number) {
  const start = startChapter ?? 1;
  const end = endChapter ?? totalAvailable;

  if (start < 1 || start > totalAvailable) {
    throw new FqNovelApiError(`startChapter 超出范围，当前目录共 ${totalAvailable} 章`);
  }

  if (end < 1 || end > totalAvailable) {
    throw new FqNovelApiError(`endChapter 超出范围，当前目录共 ${totalAvailable} 章`);
  }

  if (start > end) {
    throw new FqNovelApiError("startChapter 不能大于 endChapter");
  }

  return { start, end };
}

async function mapWithConcurrency<T, R>(
  items: T[],
  concurrency: number,
  mapper: (item: T, index: number) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length);
  let nextIndex = 0;
  const workerCount = Math.min(Math.max(concurrency, 1), items.length || 1);

  await Promise.all(
    Array.from({ length: workerCount }, async () => {
      while (true) {
        const currentIndex = nextIndex;
        nextIndex += 1;

        if (currentIndex >= items.length) {
          return;
        }

        results[currentIndex] = await mapper(items[currentIndex], currentIndex);
      }
    }),
  );

  return results;
}

function chunkArray<T>(items: T[], size: number): T[][] {
  const result: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    result.push(items.slice(index, index + size));
  }
  return result;
}

function sha1(value: string): string {
  return createHash("sha1").update(value).digest("hex");
}

function buildSelectedSignature(
  selected: TocChapter[],
  contentStyle: ContentStyle,
  includeRawContent: boolean,
): string {
  return sha1(
    JSON.stringify({
      contentStyle,
      includeRawContent,
      chapters: selected.map((chapter) => [chapter.index, chapter.chapterId, chapter.title, chapter.isFree ?? null]),
    }),
  );
}

function buildBatchSignature(batch: TocChapter[]): string {
  return sha1(
    JSON.stringify(batch.map((chapter) => [chapter.index, chapter.chapterId, chapter.title, chapter.isFree ?? null])),
  );
}

function buildEpubResumeDirPath(outputPath: string): string {
  return `${outputPath}.resume`;
}

function buildEpubResumeMetaPath(resumeDir: string): string {
  return resolve(resumeDir, "meta.json");
}

function buildEpubResumeBatchPath(resumeDir: string, batchIndex: number): string {
  return resolve(resumeDir, `batch-${String(batchIndex + 1).padStart(4, "0")}.json`);
}

async function readJsonFileIfExists<T>(path: string): Promise<T | null> {
  try {
    const raw = await readFile(path, "utf8");
    return JSON.parse(raw) as T;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException | undefined)?.code;
    if (code === "ENOENT") {
      return null;
    }
    if (error instanceof SyntaxError) {
      return null;
    }
    throw error;
  }
}

function isSameResumeMetadata(expected: EpubResumeMetadata, actual: EpubResumeMetadata | null): boolean {
  if (!actual) {
    return false;
  }

  return (
    actual.version === expected.version
    && actual.bookId === expected.bookId
    && actual.contentStyle === expected.contentStyle
    && actual.includeRawContent === expected.includeRawContent
    && actual.batchSize === expected.batchSize
    && actual.totalBatches === expected.totalBatches
    && actual.chapterRange.start === expected.chapterRange.start
    && actual.chapterRange.end === expected.chapterRange.end
    && actual.chapterRange.totalSelected === expected.chapterRange.totalSelected
    && actual.chapterRange.totalAvailable === expected.chapterRange.totalAvailable
    && actual.selectedSignature === expected.selectedSignature
  );
}

function isValidCachedChapter(chapter: unknown, expected: TocChapter, contentStyle: ContentStyle): chapter is DownloadChapter {
  if (!chapter || typeof chapter !== "object") {
    return false;
  }

  const candidate = chapter as DownloadChapter;
  return (
    candidate.index === expected.index
    && candidate.chapterId === expected.chapterId
    && typeof candidate.title === "string"
    && typeof candidate.content === "string"
    && candidate.content.trim().length > 0
    && candidate.contentStyle === contentStyle
  );
}

async function ensureEpubResumeMetadata(
  resumeDir: string,
  metadata: EpubResumeMetadata,
): Promise<void> {
  const metaPath = buildEpubResumeMetaPath(resumeDir);
  const existing = await readJsonFileIfExists<EpubResumeMetadata>(metaPath);

  if (!isSameResumeMetadata(metadata, existing)) {
    await rm(resumeDir, { recursive: true, force: true });
  }

  await mkdir(resumeDir, { recursive: true });
  await writeFile(metaPath, JSON.stringify(metadata, null, 2), "utf8");
}

async function loadEpubCachedBatch(
  resumeDir: string,
  bookId: string,
  batchIndex: number,
  batch: TocChapter[],
  contentStyle: ContentStyle,
): Promise<DownloadChapter[] | null> {
  const filePath = buildEpubResumeBatchPath(resumeDir, batchIndex);
  const cached = await readJsonFileIfExists<EpubResumeBatchCache>(filePath);
  const expectedSignature = buildBatchSignature(batch);

  if (
    !cached
    || cached.version !== EPUB_RESUME_VERSION
    || cached.bookId !== bookId
    || cached.batchIndex !== batchIndex
    || cached.batchSignature !== expectedSignature
    || !Array.isArray(cached.chapters)
    || cached.chapters.length !== batch.length
    || cached.chapters.some((chapter, index) => !isValidCachedChapter(chapter, batch[index], contentStyle))
  ) {
    if (cached) {
      await rm(filePath, { force: true });
    }
    return null;
  }

  return cached.chapters;
}

async function saveEpubCachedBatch(
  resumeDir: string,
  bookId: string,
  batchIndex: number,
  batch: TocChapter[],
  chapters: DownloadChapter[],
): Promise<void> {
  const payload: EpubResumeBatchCache = {
    version: EPUB_RESUME_VERSION,
    bookId,
    batchIndex,
    batchSignature: buildBatchSignature(batch),
    chapters,
  };

  await mkdir(resumeDir, { recursive: true });
  await writeFile(buildEpubResumeBatchPath(resumeDir, batchIndex), JSON.stringify(payload, null, 2), "utf8");
}

function sleep(ms: number): Promise<void> {
  if (ms <= 0) {
    return Promise.resolve();
  }
  return new Promise((resolvePromise) => setTimeout(resolvePromise, ms));
}

function isRiskControlBatchError(error: unknown): boolean {
  if (!(error instanceof FqNovelApiError)) {
    return false;
  }
  return /ILLEGAL_ACCESS|风控|空响应|章节内容为空\/过短|fetch failed|socket hang up|ECONNRESET|ECONNREFUSED|network error/i.test(
    error.message,
  );
}

function buildAggregateText(book: Required<Pick<BookInfo, "bookId">> & BookInfo, chapters: DownloadChapter[]): string {
  const header = [
    `书名：${book.bookName || book.bookId}`,
    book.author ? `作者：${book.author}` : undefined,
    `章节数：${chapters.length}`,
    "",
  ].filter(Boolean);

  const body = chapters.flatMap((chapter) => [
    `第${chapter.index}章 ${chapter.title}`,
    "",
    chapter.content.trim(),
    "",
  ]);

  return [...header, ...body].join("\n");
}

function resolveOutputFormat(outputPath: string, outputFormat?: OutputFormat): OutputFormat {
  if (outputFormat) {
    return outputFormat;
  }

  const ext = extname(outputPath).toLowerCase();
  if (!ext || ext === ".txt") {
    return "txt";
  }
  if (ext === ".json") {
    return "json";
  }

  throw new FqNovelApiError("outputPath 仅支持 .txt 或 .json 扩展名；也可以显式传 outputFormat");
}

function resolveOutputPath(config: AppConfig, outputPath: string): string {
  return isAbsolute(outputPath) ? outputPath : resolve(config.outputDir, outputPath);
}

function safeFileNameSegment(value: string | undefined, fallback: string): string {
  const raw = (value || fallback).trim();
  const normalized = raw.replace(/[\\/:*?"<>|\u0000-\u001F]+/g, "_").replace(/\s+/g, " ").trim();
  return normalized || fallback;
}

async function writeDownloadOutput(
  config: AppConfig,
  outputPath: string,
  outputFormat: OutputFormat,
  payload: DownloadResult,
): Promise<DownloadOutput> {
  const fullPath = resolveOutputPath(config, outputPath);
  await mkdir(dirname(fullPath), { recursive: true });

  const fileContent =
    outputFormat === "json"
      ? JSON.stringify(payload, null, 2)
      : payload.aggregateText || buildAggregateText(payload.book, payload.chapters);

  await writeFile(fullPath, fileContent, "utf8");

  return {
    path: fullPath,
    format: outputFormat,
    bytes: Buffer.byteLength(fileContent, "utf8"),
  };
}

async function prepareDownload(
  client: FqNovelClient,
  bookId: string,
  startChapter?: number,
  endChapter?: number,
): Promise<DownloadPreparation> {
  const [book, directory] = await Promise.all([
    client.getBook(bookId),
    client.getToc(bookId),
  ]);

  const normalizedBook = normalizeBookInfo(book, bookId);
  const toc = normalizeTocChapters(bookId, directory);
  if (toc.length === 0) {
    throw new FqNovelApiError(`书籍 ${bookId} 目录为空，无法执行下载`);
  }

  const range = resolveChapterRange(toc.length, startChapter, endChapter);
  const selected = toc.slice(range.start - 1, range.end);

  return {
    book: normalizedBook,
    toc,
    selected,
    chapterRange: {
      start: range.start,
      end: range.end,
      totalSelected: selected.length,
      totalAvailable: toc.length,
    },
  };
}

function resolveContentStyle(useHtmlStyle?: boolean): ContentStyle {
  return useHtmlStyle ? "html" : "txt";
}

function shouldIncludeRawContent(includeRawContent: boolean | undefined, contentStyle: ContentStyle): boolean {
  return Boolean(includeRawContent) || contentStyle === "html";
}

function resolveChapterContent(detail: ChapterInfo, contentStyle: ContentStyle): string | undefined {
  if (contentStyle === "html") {
    return detail.rawContent?.trim() || detail.txtContent?.trim();
  }
  return detail.txtContent?.trim() || detail.rawContent?.trim();
}

function toDownloadChapter(
  detail: ChapterInfo,
  fallback: TocChapter,
  contentStyle: ContentStyle,
  includeRawContent: boolean,
): DownloadChapter {
  const content = resolveChapterContent(detail, contentStyle);
  if (!content) {
    throw new FqNovelApiError(`章节 ${fallback.chapterId} 正文为空`);
  }

  const chapter: DownloadChapter = {
    index: detail.chapterIndex ?? fallback.index,
    chapterId: detail.chapterId ?? fallback.chapterId,
    title: detail.title?.trim() || fallback.title,
    content,
    contentStyle,
    wordCount: detail.wordCount,
    nextChapterId: detail.nextChapterId,
    prevChapterId: detail.prevChapterId,
    isFree: detail.isFree ?? fallback.isFree,
  };

  if (includeRawContent && detail.rawContent) {
    chapter.rawContent = detail.rawContent;
  }

  return chapter;
}

async function fetchSingleBatchWithRetry(
  client: FqNovelClient,
  bookId: string,
  batch: TocChapter[],
  contentStyle: ContentStyle,
  includeRawContent: boolean,
  options: BatchFetchOptions,
): Promise<DownloadChapter[]> {
  let lastError: unknown;

  for (let attempt = 1; attempt <= options.retryMaxAttempts; attempt += 1) {
    try {
      const response = await client.getBatchChapters(
        bookId,
        batch.map((chapter) => chapter.chapterId),
        includeRawContent,
        contentStyle === "html",
      );

      const missing = response.missingChapterIds ?? [];
      if (missing.length > 0 || response.partial) {
        throw new FqNovelApiError(`批量章节响应不完整，缺失章节: ${missing.join(", ") || "unknown"}`);
      }

      const chapterMap = new Map((response.chapters ?? []).map((chapter) => [chapter.chapterId, chapter]));
      return batch.map((chapter) => {
        const detail = chapterMap.get(chapter.chapterId);
        if (!detail) {
          throw new FqNovelApiError(`批量章节未返回 ${chapter.chapterId}`);
        }
        return toDownloadChapter(detail, chapter, contentStyle, includeRawContent);
      });
    } catch (error) {
      lastError = error;
      if (attempt >= options.retryMaxAttempts || !isRiskControlBatchError(error)) {
        throw error;
      }
      await sleep(options.retryDelayMs);
    }
  }

  throw lastError instanceof Error ? lastError : new Error("批量章节请求失败");
}

async function fetchBatchAdaptive(
  client: FqNovelClient,
  bookId: string,
  batch: TocChapter[],
  contentStyle: ContentStyle,
  includeRawContent: boolean,
  options: BatchFetchOptions,
  showProgress: boolean,
): Promise<DownloadChapter[]> {
  try {
    return await fetchSingleBatchWithRetry(client, bookId, batch, contentStyle, includeRawContent, options);
  } catch (error) {
    if (batch.length <= 1) {
      const chapter = batch[0];
      if (!chapter) {
        throw error;
      }
      if (showProgress) {
        console.error(
          `[fqnovel] fallback single chapter ${chapter.index} via /chapter reason=${error instanceof Error ? error.message : String(error)}`,
        );
      }
      const detail = await client.getChapter(
        bookId,
        chapter.chapterId,
        includeRawContent,
        contentStyle === "html",
      );
      return [toDownloadChapter(detail, chapter, contentStyle, includeRawContent)];
    }

    const middle = Math.ceil(batch.length / 2);
    const left = batch.slice(0, middle);
    const right = batch.slice(middle);

    if (showProgress) {
      const startIndex = batch[0]?.index ?? 0;
      const endIndex = batch[batch.length - 1]?.index ?? startIndex;
      console.error(
        `[fqnovel] split batch ${startIndex}-${endIndex} size=${batch.length} reason=${error instanceof Error ? error.message : String(error)}`,
      );
    }

    const leftResult = await fetchBatchAdaptive(
      client,
      bookId,
      left,
      contentStyle,
      includeRawContent,
      options,
      showProgress,
    );
    if (right.length === 0) {
      return leftResult;
    }
    const rightResult = await fetchBatchAdaptive(
      client,
      bookId,
      right,
      contentStyle,
      includeRawContent,
      options,
      showProgress,
    );
    return [...leftResult, ...rightResult];
  }
}

async function fetchChaptersViaBatch(
  client: FqNovelClient,
  bookId: string,
  selected: TocChapter[],
  contentStyle: ContentStyle,
  includeRawContent: boolean,
  concurrency: number,
  options: BatchFetchOptions,
  resumeDir?: string,
): Promise<{ chapters: DownloadChapter[]; batchInfo: BatchRequestInfo }> {
  const batchSize = clamp(options.batchSize, 1, MAX_BATCH_CHAPTERS_PER_REQUEST);
  const batches = chunkArray(selected, batchSize);
  const totalBatches = batches.length;
  const showProgress = process.env.FQNOVEL_PROGRESS === "1";

  if (resumeDir) {
    const chapters: DownloadChapter[] = [];
    let requestCount = 0;
    let cacheHitBatches = 0;
    let cachedChapters = 0;

    for (const [index, batch] of batches.entries()) {
      if (showProgress) {
        const startIndex = batch[0]?.index ?? index * batchSize + 1;
        const endIndex = batch[batch.length - 1]?.index ?? startIndex;
        console.error(
          `[fqnovel] batch ${index + 1}/${totalBatches} start chapters ${startIndex}-${endIndex} style=${contentStyle}`,
        );
      }

      const cached = await loadEpubCachedBatch(resumeDir, bookId, index, batch, contentStyle);
      if (cached) {
        cacheHitBatches += 1;
        cachedChapters += cached.length;
        chapters.push(...cached);
        if (showProgress) {
          console.error(`[fqnovel] batch ${index + 1}/${totalBatches} cache-hit chapters=${cached.length}`);
        }
        continue;
      }

      if (requestCount > 0) {
        await sleep(options.delayMs);
      }

      const result = await fetchBatchAdaptive(
        client,
        bookId,
        batch,
        contentStyle,
        includeRawContent,
        options,
        showProgress,
      );
      await saveEpubCachedBatch(resumeDir, bookId, index, batch, result);
      requestCount += 1;
      chapters.push(...result);
      if (showProgress) {
        console.error(`[fqnovel] batch ${index + 1}/${totalBatches} done chapters=${result.length}`);
      }
    }

    return {
      chapters,
      batchInfo: {
        batchSize,
        requestCount,
        totalBatches,
        cacheHitBatches,
        cachedChapters,
      },
    };
  }

  const normalizedConcurrency = clamp(concurrency, 1, 30);
  const batchResults = await mapWithConcurrency(batches, normalizedConcurrency, async (batch, index) => {
    if (showProgress) {
      const startIndex = batch[0]?.index ?? index * batchSize + 1;
      const endIndex = batch[batch.length - 1]?.index ?? startIndex;
      console.error(
        `[fqnovel] batch ${index + 1}/${totalBatches} start chapters ${startIndex}-${endIndex} style=${contentStyle}`,
      );
    }
    if (normalizedConcurrency === 1 && index > 0) {
      await sleep(options.delayMs);
    }
    const result = await fetchBatchAdaptive(
      client,
      bookId,
      batch,
      contentStyle,
      includeRawContent,
      options,
      showProgress,
    );
    if (showProgress) {
      console.error(`[fqnovel] batch ${index + 1}/${totalBatches} done chapters=${result.length}`);
    }
    return result;
  });

  return {
    chapters: batchResults.flat(),
    batchInfo: {
      batchSize,
      requestCount: batches.length,
      totalBatches,
    },
  };
}

function escapeXml(value: string | undefined): string {
  return (value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function renderParagraphs(text: string): string {
  return text
    .split(/\n+/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => `<p>${escapeXml(line)}</p>`)
    .join("\n        ");
}

function normalizeHtmlFragment(rawHtml: string): string {
  return rawHtml
    .trim()
    .replace(/<\?xml[\s\S]*?\?>/gi, "")
    .replace(/<!DOCTYPE[\s\S]*?>/gi, "")
    .replace(/<\/?(?:html|head|body)[^>]*>/gi, "")
    .replace(/<blk\b[^>]*>/gi, '<p class="blk">')
    .replace(/<\/blk>/gi, "</p>")
    .trim();
}

function renderChapterBody(book: Required<Pick<BookInfo, "bookId">> & BookInfo, chapter: DownloadChapter): string {
  if (chapter.contentStyle === "html") {
    return [
      `<h2 class="book-title">${escapeXml(book.bookName || book.bookId)}</h2>`,
      normalizeHtmlFragment(chapter.content),
    ].join("\n    ");
  }

  return [
    `<h1>${escapeXml(chapter.title)}</h1>`,
    `<h2>${escapeXml(book.bookName || book.bookId)}</h2>`,
    renderParagraphs(chapter.content),
  ].join("\n    ");
}

function buildChapterXhtml(book: Required<Pick<BookInfo, "bookId">> & BookInfo, chapter: DownloadChapter): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
  <head>
    <title>${escapeXml(chapter.title)}</title>
    <link rel="stylesheet" type="text/css" href="../Styles/book.css" />
  </head>
  <body>
    ${renderChapterBody(book, chapter)}
  </body>
</html>`;
}

function buildCoverXhtml(book: Required<Pick<BookInfo, "bookId">> & BookInfo, coverFileName: string): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
  <head>
    <title>${escapeXml(book.bookName || book.bookId)}</title>
    <link rel="stylesheet" type="text/css" href="../Styles/book.css" />
  </head>
  <body class="cover-page">
    <section>
      <img src="../Images/${escapeXml(coverFileName)}" alt="${escapeXml(book.bookName || book.bookId)}" class="cover-image" />
      <h1>${escapeXml(book.bookName || book.bookId)}</h1>
      ${book.author ? `<p class="cover-author">${escapeXml(book.author)}</p>` : ""}
    </section>
  </body>
</html>`;
}

function buildStyles(): string {
  return `body { font-family: serif; line-height: 1.6; margin: 5%; }
h1 { font-size: 1.5em; margin-bottom: 0.8em; }
h2, .book-title { font-size: 1em; color: #666; margin-top: 0; margin-bottom: 1.5em; }
p, .blk { text-indent: 2em; margin: 0.6em 0; display: block; }
.cover-page { text-align: center; }
.cover-image { max-width: 100%; max-height: 90vh; object-fit: contain; }
.cover-author { color: #666; }`;
}

function buildNcx(book: Required<Pick<BookInfo, "bookId">> & BookInfo, chapters: DownloadChapter[], includeCover: boolean): string {
  const navPoints = chapters
    .map((chapter, index) => {
      const playOrder = index + 1 + (includeCover ? 1 : 0);
      const fileName = `chapter-${String(index + 1).padStart(4, "0")}.xhtml`;
      return `    <navPoint id="navPoint-${playOrder}" playOrder="${playOrder}">
      <navLabel><text>${escapeXml(chapter.title)}</text></navLabel>
      <content src="Text/${fileName}" />
    </navPoint>`;
    })
    .join("\n");

  const coverNavPoint = includeCover
    ? `    <navPoint id="navPoint-cover" playOrder="1">
      <navLabel><text>封面</text></navLabel>
      <content src="Text/cover.xhtml" />
    </navPoint>\n`
    : "";

  return `<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:fqnovel:book:${escapeXml(book.bookId)}" />
    <meta name="dtb:depth" content="1" />
    <meta name="dtb:totalPageCount" content="0" />
    <meta name="dtb:maxPageNumber" content="0" />
  </head>
  <docTitle><text>${escapeXml(book.bookName || book.bookId)}</text></docTitle>
  <navMap>
${coverNavPoint}${navPoints}
  </navMap>
</ncx>`;
}

function buildOpf(
  book: Required<Pick<BookInfo, "bookId">> & BookInfo,
  chapters: DownloadChapter[],
  cover: CoverAsset | null,
): string {
  const manifestChapters = chapters
    .map((chapter, index) => {
      const itemId = `chapter-${index + 1}`;
      const fileName = `Text/chapter-${String(index + 1).padStart(4, "0")}.xhtml`;
      return `    <item id="${itemId}" href="${fileName}" media-type="application/xhtml+xml" />`;
    })
    .join("\n");

  const spineChapters = chapters
    .map((_, index) => `    <itemref idref="chapter-${index + 1}" />`)
    .join("\n");

  const coverMeta = cover ? `    <meta name="cover" content="cover-image" />\n` : "";
  const coverManifest = cover
    ? `    <item id="cover-image" href="Images/${cover.fileName}" media-type="${cover.mediaType}" />
    <item id="cover-page" href="Text/cover.xhtml" media-type="application/xhtml+xml" />
`
    : "";
  const coverSpine = cover ? "    <itemref idref=\"cover-page\" linear=\"yes\" />\n" : "";
  const guide = cover
    ? `  <guide>
    <reference type="cover" title="封面" href="Text/cover.xhtml" />
  </guide>`
    : "";

  return `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${escapeXml(book.bookName || book.bookId)}</dc:title>
    <dc:creator>${escapeXml(book.author || "未知作者")}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId">urn:fqnovel:book:${escapeXml(book.bookId)}</dc:identifier>
    <dc:description>${escapeXml(book.description || "")}</dc:description>
${coverMeta}  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml" />
    <item id="style" href="Styles/book.css" media-type="text/css" />
${coverManifest}${manifestChapters}
  </manifest>
  <spine toc="ncx">
${coverSpine}${spineChapters}
  </spine>
${guide}
</package>`;
}

function defaultEpubOutputPath(config: AppConfig, book: Required<Pick<BookInfo, "bookId">> & BookInfo): string {
  const fileName = `${safeFileNameSegment(book.bookName, book.bookId)}-${book.bookId}.epub`;
  return resolve(config.outputDir, fileName);
}

function inferMediaType(url: string, contentTypeHeader: string | null): { mediaType: string; extension: string } {
  const normalizedHeader = (contentTypeHeader || "").split(";")[0].trim().toLowerCase();
  if (normalizedHeader.startsWith("image/")) {
    const extension = normalizedHeader.split("/")[1] || "img";
    return { mediaType: normalizedHeader, extension };
  }

  const pathname = new URL(url).pathname.toLowerCase();
  if (pathname.endsWith(".png")) return { mediaType: "image/png", extension: "png" };
  if (pathname.endsWith(".gif")) return { mediaType: "image/gif", extension: "gif" };
  if (pathname.endsWith(".webp")) return { mediaType: "image/webp", extension: "webp" };
  if (pathname.endsWith(".heic")) return { mediaType: "image/heic", extension: "heic" };
  return { mediaType: "image/jpeg", extension: "jpg" };
}

async function convertCoverToJpeg(data: Buffer): Promise<Buffer> {
  try {
    return await sharp(data, { failOn: "none" })
      .rotate()
      .flatten({ background: "#ffffff" })
      .jpeg({ quality: 90 })
      .toBuffer();
  } catch (sharpError) {
    try {
      const converted = await heicConvert({
        buffer: data,
        format: "JPEG",
        quality: 0.9,
      });
      return Buffer.from(converted);
    } catch (heicError) {
      const sharpMessage = sharpError instanceof Error ? sharpError.message : String(sharpError);
      const heicMessage = heicError instanceof Error ? heicError.message : String(heicError);
      throw new Error(`封面转码失败: sharp=${sharpMessage}; heic-convert=${heicMessage}`);
    }
  }
}

async function fetchCoverAsset(coverUrl: string | undefined): Promise<{ asset: CoverAsset | null; error?: string }> {
  if (!coverUrl) {
    return { asset: null };
  }

  try {
    const response = await fetch(coverUrl, {
      headers: { accept: "image/*" },
    });
    if (!response.ok) {
      return { asset: null, error: `封面下载失败: ${response.status}` };
    }

    const arrayBuffer = await response.arrayBuffer();
    const converted = await convertCoverToJpeg(Buffer.from(arrayBuffer));
    return {
      asset: {
        fileName: "cover.jpg",
        mediaType: "image/jpeg",
        data: converted,
      },
    };
  } catch (error) {
    return {
      asset: null,
      error: error instanceof Error ? error.message : "封面下载失败",
    };
  }
}

async function writeBinaryOutput(config: AppConfig, outputPath: string, buffer: Buffer, format: "epub"): Promise<DownloadOutput> {
  const fullPath = resolveOutputPath(config, outputPath);
  await mkdir(dirname(fullPath), { recursive: true });
  await writeFile(fullPath, buffer);

  return {
    path: fullPath,
    format,
    bytes: buffer.byteLength,
  };
}

export async function downloadBook(
  client: FqNovelClient,
  config: AppConfig,
  options: DownloadOptions,
): Promise<DownloadResult> {
  const prepared = await prepareDownload(client, options.bookId, options.startChapter, options.endChapter);
  const contentStyle = resolveContentStyle(options.useHtmlStyle);
  const includeRawContent = shouldIncludeRawContent(options.includeRawContent, contentStyle);
  const { chapters, batchInfo } = await fetchChaptersViaBatch(
    client,
    options.bookId,
    prepared.selected,
    contentStyle,
    includeRawContent,
    options.concurrency ?? config.downloadConcurrency,
    {
      batchSize: MAX_BATCH_CHAPTERS_PER_REQUEST,
      delayMs: 0,
      retryMaxAttempts: 1,
      retryDelayMs: 0,
    },
  );

  const aggregateText = options.aggregateText === false ? undefined : buildAggregateText(prepared.book, chapters);

  const payload: DownloadResult = {
    book: prepared.book,
    chapterRange: prepared.chapterRange,
    contentStyle,
    batchInfo,
    chapters,
    aggregateText,
  };

  if (options.outputPath) {
    const format = resolveOutputFormat(options.outputPath, options.outputFormat);
    payload.output = await writeDownloadOutput(config, options.outputPath, format, payload);
  }

  return payload;
}

export async function buildEpub(
  client: FqNovelClient,
  config: AppConfig,
  options: EpubOptions,
): Promise<EpubResult> {
  const prepared = await prepareDownload(client, options.bookId, options.startChapter, options.endChapter);
  const contentStyle = resolveContentStyle(options.useHtmlStyle);
  const includeRawContent = contentStyle === "html";
  const keepResumeCache = options.keepResumeCache === true;
  const outputPath = options.outputPath
    ? resolveOutputPath(config, options.outputPath)
    : defaultEpubOutputPath(config, prepared.book);
  const resumeDir = buildEpubResumeDirPath(outputPath);
  const totalBatches = chunkArray(prepared.selected, EPUB_BATCH_SIZE).length;

  await ensureEpubResumeMetadata(resumeDir, {
    version: EPUB_RESUME_VERSION,
    bookId: options.bookId,
    contentStyle,
    includeRawContent,
    batchSize: EPUB_BATCH_SIZE,
    totalBatches,
    chapterRange: prepared.chapterRange,
    selectedSignature: buildSelectedSignature(prepared.selected, contentStyle, includeRawContent),
  });

  const { chapters, batchInfo } = await fetchChaptersViaBatch(
    client,
    options.bookId,
    prepared.selected,
    contentStyle,
    includeRawContent,
    1,
    {
      batchSize: EPUB_BATCH_SIZE,
      delayMs: EPUB_BATCH_DELAY_MS,
      retryMaxAttempts: EPUB_BATCH_RETRY_MAX_ATTEMPTS,
      retryDelayMs: EPUB_BATCH_RETRY_DELAY_MS,
    },
    resumeDir,
  );

  const { asset: coverAsset, error: coverError } = await fetchCoverAsset(prepared.book.coverUrl);
  const zip = new JSZip();
  zip.file("mimetype", "application/epub+zip", { compression: "STORE" });
  zip.file(
    "META-INF/container.xml",
    `<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml" />
  </rootfiles>
</container>`,
  );
  zip.file("OEBPS/Styles/book.css", buildStyles());

  if (coverAsset) {
    zip.file(`OEBPS/Images/${coverAsset.fileName}`, coverAsset.data);
    zip.file("OEBPS/Text/cover.xhtml", buildCoverXhtml(prepared.book, coverAsset.fileName));
  }

  chapters.forEach((chapter, index) => {
    const fileName = `OEBPS/Text/chapter-${String(index + 1).padStart(4, "0")}.xhtml`;
    zip.file(fileName, buildChapterXhtml(prepared.book, chapter));
  });

  zip.file("OEBPS/toc.ncx", buildNcx(prepared.book, chapters, Boolean(coverAsset)));
  zip.file("OEBPS/content.opf", buildOpf(prepared.book, chapters, coverAsset));

  const epubBuffer = await zip.generateAsync({
    type: "nodebuffer",
    mimeType: "application/epub+zip",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
  });

  const output = await writeBinaryOutput(config, outputPath, epubBuffer, "epub");
  if (!keepResumeCache) {
    await rm(resumeDir, { recursive: true, force: true });
  }

  return {
    book: prepared.book,
    chapterRange: prepared.chapterRange,
    contentStyle,
    batchInfo,
    output,
    resumeDir,
    resumeCacheKept: keepResumeCache,
    coverUrl: prepared.book.coverUrl,
    coverEmbedded: Boolean(coverAsset),
    coverError,
  };
}

export function buildTocSummary(bookId: string, directory: { itemDataList?: Array<{ itemId: string; title?: string; isFree?: boolean }> | undefined }) {
  const chapters = normalizeTocChapters(bookId, directory);
  return {
    bookId,
    totalChapters: chapters.length,
    chapters,
  };
}