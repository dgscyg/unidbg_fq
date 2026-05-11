package com.mengying.fqnovel.web;

import com.mengying.fqnovel.dto.FQBatchChaptersRequest;
import com.mengying.fqnovel.dto.FQBatchChaptersResponse;
import com.mengying.fqnovel.dto.FQNovelBookInfo;
import com.mengying.fqnovel.dto.FQNovelChapterInfo;
import com.mengying.fqnovel.dto.FQNovelRequest;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.service.FQBatchChapterService;
import com.mengying.fqnovel.service.FQChapterPrefetchService;
import com.mengying.fqnovel.service.FQNovelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * FQNovel API 控制器（精简版，仅支持 Legado 阅读）
 * 提供小说书籍和章节内容获取接口
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class FQNovelController {

    private static final Logger log = LoggerFactory.getLogger(FQNovelController.class);

    private final FQNovelService fqNovelService;
    private final FQChapterPrefetchService fqChapterPrefetchService;
    private final FQBatchChapterService fqBatchChapterService;

    public FQNovelController(
        FQNovelService fqNovelService,
        FQChapterPrefetchService fqChapterPrefetchService,
        FQBatchChapterService fqBatchChapterService
    ) {
        this.fqNovelService = fqNovelService;
        this.fqChapterPrefetchService = fqChapterPrefetchService;
        this.fqBatchChapterService = fqBatchChapterService;
    }

    /**
     * 获取书籍详情（精简版 - 仅返回 Legado 需要的字段）
     * 路径: /book/{bookId}（bookId 仅允许数字）
     * 
     * @param bookId 书籍ID
     * @return 书籍详情信息（精简）
     */
    @GetMapping("/book/{bookId:\\d+}")
    public CompletableFuture<FQNovelResponse<FQNovelBookInfo>> getBookInfo(@PathVariable String bookId) {
        if (log.isDebugEnabled()) {
            log.debug("获取书籍信息 - bookId: {}", bookId);
        }
        return fqNovelService.getBookInfo(bookId);
    }

    /**
     * 获取章节正文
     * 路径: /chapter/{bookId}/{chapterId}（bookId/chapterId 仅允许数字）
     * 
     * @param bookId 书籍ID
     * @param chapterId 章节ID
     * @return 章节内容信息
     */
    @GetMapping("/chapter/{bookId:\\d+}/{chapterId:\\d+}")
    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContent(
            @PathVariable String bookId,
            @PathVariable String chapterId) {

        if (log.isDebugEnabled()) {
            log.debug("获取章节内容 - bookId: {}, chapterId: {}", bookId, chapterId);
        }

        FQNovelRequest request = new FQNovelRequest();
        request.setBookId(bookId);
        request.setChapterId(chapterId);
        return fqChapterPrefetchService.getChapterContent(request);
    }

    /**
     * 批量获取章节正文（必须走 batch_full 链路）。
     */
    @PostMapping(value = "/chapters/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<FQNovelResponse<FQBatchChaptersResponse>> getBatchChapterContent(
            @RequestBody FQBatchChaptersRequest request) {

        if (log.isDebugEnabled()) {
            log.debug("批量获取章节内容 - bookId: {}, chapterIds: {}",
                request == null ? null : request.getBookId(),
                request == null ? null : request.getChapterIds());
        }

        return fqBatchChapterService.getBatchChapters(request);
    }
}
