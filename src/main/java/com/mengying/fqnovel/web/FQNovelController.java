package com.mengying.fqnovel.web;

import com.mengying.fqnovel.dto.FQNovelBookInfo;
import com.mengying.fqnovel.dto.FQNovelChapterInfo;
import com.mengying.fqnovel.dto.FQNovelRequest;
import com.mengying.fqnovel.dto.FQNovelResponse;
import com.mengying.fqnovel.service.FQChapterPrefetchService;
import com.mengying.fqnovel.service.FQNovelService;
import com.mengying.fqnovel.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
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

    public FQNovelController(FQNovelService fqNovelService, FQChapterPrefetchService fqChapterPrefetchService) {
        this.fqNovelService = fqNovelService;
        this.fqChapterPrefetchService = fqChapterPrefetchService;
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
     * 兼容 fqfix 书源的章节内容端点。
     * 调用格式: GET /content?item_id={chapterId}&book_id={bookId}
     * 返回格式: {"code":0,"data":{"content":"正文HTML"}}，匹配 fqfix 的 $.data.content 规则。
     */
    @GetMapping("/content")
    public CompletableFuture<Map<String, Object>> getContent(
            @RequestParam("item_id") String itemId,
            @RequestParam(value = "book_id", required = false) String bookId) {

        if (log.isDebugEnabled()) {
            log.debug("fqfix内容接口 - book_id: {}, item_id: {}", bookId, itemId);
        }

        String trimmedItemId = Texts.trimToNull(itemId);
        if (trimmedItemId == null || !Texts.isDigits(trimmedItemId)) {
            return CompletableFuture.completedFuture(Map.of(
                "code", -1, "message", "item_id 无效"));
        }
        String trimmedBookId = Texts.trimToNull(bookId);
        if (trimmedBookId != null && !Texts.isDigits(trimmedBookId)) {
            return CompletableFuture.completedFuture(Map.of(
                "code", -1, "message", "book_id 无效"));
        }

        // book_id 优先用参数，其次尝试从 chapterUrl 的 info 中恢复（fqfix b64 编码格式）
        // 实际 fqfix 调用时 book_id 会通过 tocUrl 已知的 book_id 变量注入
        if (trimmedBookId == null) {
            return CompletableFuture.completedFuture(Map.of(
                "code", -1, "message", "book_id 必填"));
        }

        FQNovelRequest request = new FQNovelRequest();
        request.setBookId(trimmedBookId);
        request.setChapterId(trimmedItemId);

        return fqChapterPrefetchService.getChapterContent(request)
            .thenApply(response -> {
                if (response == null || response.code() == null || response.code() != 0
                    || response.data() == null) {
                    String msg = response != null ? response.message() : "章节获取失败";
                    return Map.<String, Object>of("code", -1, "message", msg);
                }
                FQNovelChapterInfo chapter = response.data();
                // fqfix 期望 $.data.content 返回 HTML 正文
                String content = Texts.firstNonBlank(
                    chapter.getTxtContent(),
                    chapter.getRawContent(),
                    chapter.getTitle()
                );
                return Map.<String, Object>of("code", 0, "data", Map.of("content", content));
            });
    }
}
