package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQDownloadProperties;
import com.mengying.fqnovel.dto.FQNovelChapterInfo;
import com.mengying.fqnovel.dto.FQNovelData;
import com.mengying.fqnovel.dto.ItemContent;
import com.mengying.fqnovel.utils.HtmlTextExtractor;
import com.mengying.fqnovel.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.BadPaddingException;

/**
 * 章节内容构建器（从 FQChapterPrefetchService 拆分而来）。
 * <p>
 * 负责：
 * <ul>
 *   <li>校验上游 ItemContent</li>
 *   <li>调用 FqCrypto 解密 + GZIP 解压</li>
 *   <li>BadPadding 时自动刷新 registerkey 并重试一次</li>
 *   <li>从 HTML 提取纯文本和标题</li>
 *   <li>组装 FQNovelChapterInfo DTO</li>
 * </ul>
 */
@Component
public class ChapterContentBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChapterContentBuilder.class);

    /**
     * Base64 编码的最小有效长度（16 bytes IV + 至少1 byte数据 = 24 chars）
     */
    private static final int MIN_BASE64_ENCRYPTED_LENGTH = 24;
    private static final String DEFAULT_CHAPTER_TITLE = "章节标题";
    private static final String DEFAULT_AUTHOR_NAME = "未知作者";

    private final FQRegisterKeyService registerKeyService;
    private final FQDownloadProperties downloadProperties;

    public ChapterContentBuilder(FQRegisterKeyService registerKeyService, FQDownloadProperties downloadProperties) {
        this.registerKeyService = registerKeyService;
        this.downloadProperties = downloadProperties;
    }

    /**
     * 从上游 ItemContent 构建章节信息。
     *
     * @param bookId      书籍 ID
     * @param chapterId   章节 ID
     * @param itemContent 上游返回的章节内容
     * @return 构建好的 FQNovelChapterInfo
     * @throws Exception 解密、解压或校验失败
     */
    public FQNovelChapterInfo buildChapterInfo(String bookId, String chapterId, ItemContent itemContent) throws Exception {
        return buildChapterInfo(
            bookId,
            chapterId,
            itemContent,
            downloadProperties.getCache().isChapterIncludeRawContent()
        );
    }

    public FQNovelChapterInfo buildChapterInfo(
        String bookId,
        String chapterId,
        ItemContent itemContent,
        boolean includeRawContent
    ) throws Exception {
        if (itemContent == null) {
            throw new IllegalArgumentException("章节内容为空");
        }
        if (itemContent.code() != 0) {
            throw new IllegalStateException("upstream item code=" + itemContent.code());
        }
        String encrypted = Texts.trimToNull(itemContent.content());
        if (encrypted == null) {
            throw new IllegalArgumentException("章节内容为空/过短");
        }
        if (encrypted.length() < MIN_BASE64_ENCRYPTED_LENGTH) {
            throw new IllegalArgumentException("章节内容为空/过短");
        }

        Long contentKeyver = itemContent.keyVersion();
        String decryptedContent = decryptChapterContentWithRetry(bookId, chapterId, encrypted, contentKeyver);
        return buildChapterInfoFromContent(
            bookId,
            chapterId,
            itemContent,
            decryptedContent,
            includeRawContent
        );
    }

    // ── 内部方法 ─────────────────────────────────────────────────

    private static FQNovelChapterInfo buildChapterInfoFromContent(
        String bookId,
        String chapterId,
        ItemContent itemContent,
        String decryptedContent,
        boolean includeRawContent
    ) {
        String txtContent = HtmlTextExtractor.extractText(decryptedContent);

        FQNovelChapterInfo chapterInfo = new FQNovelChapterInfo();
        chapterInfo.setChapterId(chapterId);
        chapterInfo.setBookId(bookId);
        if (includeRawContent) {
            chapterInfo.setRawContent(decryptedContent);
        }
        chapterInfo.setTxtContent(txtContent);
        chapterInfo.setTitle(resolveChapterTitle(itemContent, decryptedContent));
        chapterInfo.setAuthorName(resolveAuthorName(itemContent));
        chapterInfo.setWordCount(txtContent.length());
        chapterInfo.setUpdateTime(System.currentTimeMillis());

        return chapterInfo;
    }

    private static String resolveChapterTitle(ItemContent itemContent, String decryptedContent) {
        String title = itemContent == null ? null : Texts.trimToNull(itemContent.title());
        if (title != null) {
            return title;
        }
        String extractedTitle = HtmlTextExtractor.extractTitle(decryptedContent);
        return extractedTitle != null ? extractedTitle : DEFAULT_CHAPTER_TITLE;
    }

    private static String resolveAuthorName(ItemContent itemContent) {
        if (itemContent == null) {
            return DEFAULT_AUTHOR_NAME;
        }
        FQNovelData novelData = itemContent.novelData();
        if (novelData == null) {
            return DEFAULT_AUTHOR_NAME;
        }
        return Texts.defaultIfBlank(novelData.author(), DEFAULT_AUTHOR_NAME);
    }

    /**
     * BadPadding 通常意味着 key 不匹配。这里做一次受控重试：刷新 registerkey 后仅再尝试一次。
     */
    private String decryptChapterContentWithRetry(String bookId, String chapterId, String encrypted, Long contentKeyver) throws Exception {
        String key = registerKeyService.getDecryptionKey(contentKeyver);
        try {
            return FqCrypto.decryptAndDecompressContent(encrypted, key);
        } catch (BadPaddingException first) {
            log.warn("章节解密失败(BadPadding)，刷新registerkey后重试一次 - bookId: {}, chapterId: {}, keyver={}",
                bookId, chapterId, contentKeyver);
            registerKeyService.invalidateCurrentKey();
            registerKeyService.refreshRegisterKey();
            String retryKey = registerKeyService.getDecryptionKey(contentKeyver);
            return FqCrypto.decryptAndDecompressContent(encrypted, retryKey);
        }
    }
}
