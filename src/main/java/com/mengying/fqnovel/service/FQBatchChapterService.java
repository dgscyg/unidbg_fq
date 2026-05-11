package com.mengying.fqnovel.service;

import com.mengying.fqnovel.dto.FQBatchChaptersRequest;
import com.mengying.fqnovel.dto.FQBatchChaptersResponse;
import com.mengying.fqnovel.dto.FQDirectoryRequest;
import com.mengying.fqnovel.dto.FQDirectoryResponse;
import com.mengying.fqnovel.dto.FQNovelChapterInfo;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.dto.FqIBatchFullResponse;
import com.mengying.fqnovel.dto.ItemContent;
import com.mengying.fqnovel.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

@Service
public class FQBatchChapterService {

    private static final Logger log = LoggerFactory.getLogger(FQBatchChapterService.class);
    public static final int MAX_BATCH_CHAPTER_SIZE = 30;

    private final FQNovelService fqNovelService;
    private final FQDirectoryService fqDirectoryService;
    private final ChapterContentBuilder chapterContentBuilder;

    public FQBatchChapterService(
        FQNovelService fqNovelService,
        FQDirectoryService fqDirectoryService,
        ChapterContentBuilder chapterContentBuilder
    ) {
        this.fqNovelService = fqNovelService;
        this.fqDirectoryService = fqDirectoryService;
        this.chapterContentBuilder = chapterContentBuilder;
    }

    public CompletableFuture<FQNovelResponse<FQBatchChaptersResponse>> getBatchChapters(FQBatchChaptersRequest request) {
        String bookId = request == null ? null : Texts.trimToNull(request.getBookId());
        if (!Texts.isDigits(bookId)) {
            return CompletableFuture.completedFuture(FQNovelResponse.error("书籍ID必须为纯数字"));
        }

        List<String> chapterIds = normalizeChapterIds(request == null ? null : request.getChapterIds());
        if (chapterIds.isEmpty()) {
            return CompletableFuture.completedFuture(FQNovelResponse.error("chapterIds 不能为空"));
        }
        if (chapterIds.size() > MAX_BATCH_CHAPTER_SIZE) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.error("单次批量章节数量不能超过 " + MAX_BATCH_CHAPTER_SIZE)
            );
        }

        boolean includeRawContent = Boolean.TRUE.equals(request.getIncludeRawContent());
        FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
        directoryRequest.setBookId(bookId);
        directoryRequest.setMinimalResponse(false);

        return fqDirectoryService.getBookDirectory(directoryRequest)
            .thenCompose(directoryResponse -> {
                if (!RequestCacheHelper.isResponseSuccessWithData(directoryResponse)) {
                    String message = directoryResponse == null
                        ? "获取目录失败"
                        : Texts.defaultIfBlank(directoryResponse.message(), "获取目录失败");
                    return CompletableFuture.completedFuture(FQNovelResponse.<FQBatchChaptersResponse>error(message));
                }

                ChapterCatalog catalog = ChapterCatalog.from(directoryResponse.data());
                String joinedIds = String.join(",", chapterIds);
                return fqNovelService.batchFull(joinedIds, bookId, true)
                    .thenApply(batchResponse -> mapBatchResponse(bookId, chapterIds, includeRawContent, catalog, batchResponse));
            })
            .exceptionally(error -> handleFailure(bookId, error));
    }

    private FQNovelResponse<FQBatchChaptersResponse> mapBatchResponse(
        String bookId,
        List<String> chapterIds,
        boolean includeRawContent,
        ChapterCatalog catalog,
        FQNovelResponse<FqIBatchFullResponse> batchResponse
    ) {
        if (!RequestCacheHelper.isResponseSuccessWithData(batchResponse)) {
            return FQNovelResponse.error(batchResponse == null ? "批量章节请求失败" : batchResponse.message());
        }

        Map<String, ItemContent> dataMap = batchResponse.data().data();
        if (dataMap == null || dataMap.isEmpty()) {
            return FQNovelResponse.error("批量章节响应为空");
        }

        List<FQNovelChapterInfo> chapters = new ArrayList<>(chapterIds.size());
        List<String> missingChapterIds = new ArrayList<>();

        for (String chapterId : chapterIds) {
            ItemContent itemContent = dataMap.get(chapterId);
            if (itemContent == null) {
                missingChapterIds.add(chapterId);
                continue;
            }
            try {
                FQNovelChapterInfo chapterInfo = chapterContentBuilder.buildChapterInfo(
                    bookId,
                    chapterId,
                    itemContent,
                    includeRawContent
                );
                catalog.enrich(chapterInfo, chapterId);
                chapters.add(chapterInfo);
            } catch (Exception e) {
                throw new CompletionException(
                    new IllegalStateException("章节解析失败: " + chapterId + ", " + Texts.defaultIfBlank(e.getMessage(), e.toString()), e)
                );
            }
        }

        if (chapters.isEmpty()) {
            return FQNovelResponse.error("批量章节未返回任何有效内容");
        }

        return FQNovelResponse.success(new FQBatchChaptersResponse(
            bookId,
            chapterIds.size(),
            chapters.size(),
            !missingChapterIds.isEmpty(),
            missingChapterIds,
            chapters
        ));
    }

    private FQNovelResponse<FQBatchChaptersResponse> handleFailure(String bookId, Throwable error) {
        Throwable resolved = error instanceof CompletionException && error.getCause() != null
            ? error.getCause()
            : error;
        log.error("批量章节获取失败 - bookId: {}", bookId, resolved);
        String message = resolved == null
            ? "未知错误"
            : Texts.defaultIfBlank(resolved.getMessage(), resolved.toString());
        return FQNovelResponse.error("批量章节获取失败: " + message);
    }

    private static List<String> normalizeChapterIds(List<String> chapterIds) {
        if (chapterIds == null || chapterIds.isEmpty()) {
            return List.of();
        }
        return chapterIds.stream()
            .map(Texts::trimToNull)
            .filter(Texts::isDigits)
            .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    private record ChapterMeta(
        int chapterIndex,
        String title,
        String prevChapterId,
        String nextChapterId,
        Boolean isFree
    ) {
    }

    private record ChapterCatalog(Map<String, ChapterMeta> chapters) {

        private static ChapterCatalog from(FQDirectoryResponse directoryResponse) {
            if (directoryResponse == null || directoryResponse.getItemDataList() == null) {
                return new ChapterCatalog(Map.of());
            }
            List<FQDirectoryResponse.ItemData> items = directoryResponse.getItemDataList();
            java.util.LinkedHashMap<String, ChapterMeta> chapterMap = new java.util.LinkedHashMap<>();
            for (int index = 0; index < items.size(); index++) {
                FQDirectoryResponse.ItemData item = items.get(index);
                if (item == null || !Texts.hasText(item.getItemId())) {
                    continue;
                }
                String itemId = Texts.trimToEmpty(item.getItemId());
                String prevChapterId = findNeighborId(items, index - 1);
                String nextChapterId = findNeighborId(items, index + 1);
                chapterMap.put(itemId, new ChapterMeta(
                    index + 1,
                    Texts.trimToEmpty(item.getTitle()),
                    prevChapterId,
                    nextChapterId,
                    item.getIsFree()
                ));
            }
            return new ChapterCatalog(Collections.unmodifiableMap(chapterMap));
        }

        private static String findNeighborId(List<FQDirectoryResponse.ItemData> items, int index) {
            if (index < 0 || items == null || index >= items.size()) {
                return null;
            }
            FQDirectoryResponse.ItemData item = items.get(index);
            return item == null ? null : Texts.trimToNull(item.getItemId());
        }

        private void enrich(FQNovelChapterInfo chapterInfo, String chapterId) {
            if (chapterInfo == null || !Texts.hasText(chapterId)) {
                return;
            }
            ChapterMeta meta = chapters.get(chapterId);
            if (meta == null) {
                return;
            }
            chapterInfo.setChapterIndex(meta.chapterIndex());
            chapterInfo.setPrevChapterId(meta.prevChapterId());
            chapterInfo.setNextChapterId(meta.nextChapterId());
            chapterInfo.setIsFree(meta.isFree());
            if (!Texts.hasText(chapterInfo.getTitle()) || "章节标题".equals(chapterInfo.getTitle())) {
                chapterInfo.setTitle(Texts.defaultIfBlank(meta.title(), chapterInfo.getTitle()));
            }
        }
    }
}