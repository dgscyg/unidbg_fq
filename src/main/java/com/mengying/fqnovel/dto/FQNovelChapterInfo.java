package com.mengying.fqnovel.dto;

import com.mengying.fqnovel.utils.Texts;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.function.Consumer;

/**
 * FQNovel 小说章节信息
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FQNovelChapterInfo {

    /**
     * 章节ID
     */
    private String chapterId;

    /**
     * 书籍ID
     */
    private String bookId;

    /**
     * 作者名称
     */
    private String authorName;

    /**
     * 章节标题
     */
    private String title;

    /**
     * 原始章节内容-html
     */
    private String rawContent;

    /**
     * 章节序号
     */
    private Integer chapterIndex;

    /**
     * 字数
     */
    private Integer wordCount;

    /**
     * 更新时间
     */
    private Long updateTime;

    /**
     * 上一章节ID
     */
    private String prevChapterId;

    /**
     * 下一章节ID
     */
    private String nextChapterId;

    /**
     * 是否免费章节
     */
    private Boolean isFree;

    /**
     * 纯文本内容（从HTML中提取的纯文本）
     */
    private String txtContent;

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public Integer getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(Integer chapterIndex) {
        this.chapterIndex = chapterIndex;
    }

    public Integer getWordCount() {
        return wordCount;
    }

    public void setWordCount(Integer wordCount) {
        this.wordCount = wordCount;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }

    public String getPrevChapterId() {
        return prevChapterId;
    }

    public void setPrevChapterId(String prevChapterId) {
        this.prevChapterId = prevChapterId;
    }

    public String getNextChapterId() {
        return nextChapterId;
    }

    public void setNextChapterId(String nextChapterId) {
        this.nextChapterId = nextChapterId;
    }

    public Boolean getIsFree() {
        return isFree;
    }

    public void setIsFree(Boolean free) {
        isFree = free;
    }

    public String getTxtContent() {
        return txtContent;
    }

    public void setTxtContent(String txtContent) {
        this.txtContent = txtContent;
    }

    public boolean hasRawContent() {
        return Texts.hasText(rawContent);
    }

    public FQNovelChapterInfo copyForResponse(boolean includeRawContent, boolean useHtmlStyle) {
        FQNovelChapterInfo copy = new FQNovelChapterInfo();
        copy.setChapterId(chapterId);
        copy.setBookId(bookId);
        copy.setAuthorName(authorName);
        copy.setTitle(title);
        copy.setRawContent(includeRawContent ? rawContent : null);
        copy.setChapterIndex(chapterIndex);
        copy.setWordCount(wordCount);
        copy.setUpdateTime(updateTime);
        copy.setPrevChapterId(prevChapterId);
        copy.setNextChapterId(nextChapterId);
        copy.setIsFree(isFree);
        copy.setTxtContent(useHtmlStyle && Texts.hasText(rawContent) ? rawContent : txtContent);
        return copy;
    }

    /**
     * 缓存写入前的统一校验：
     * 1. 标准化并校验 bookId/chapterId；
     * 2. 校验 title/txtContent 必填内容。
     */
    public static boolean normalizeAndValidateForCache(String bookId, String chapterId, FQNovelChapterInfo chapterInfo) {
        if (chapterInfo == null) {
            return false;
        }

        String normalizedBookId = normalizeRequiredId(bookId);
        String normalizedChapterId = normalizeRequiredId(chapterId);
        if (normalizedBookId == null || normalizedChapterId == null) {
            return false;
        }

        return normalizeIdentityAndCheck(chapterInfo, normalizedBookId, normalizedChapterId)
            && isContentValid(chapterInfo);
    }

    private static boolean normalizeIdentityAndCheck(
        FQNovelChapterInfo chapterInfo,
        String normalizedBookId,
        String normalizedChapterId
    ) {
        if (!normalizeAndMatchId(chapterInfo.getBookId(), normalizedBookId, chapterInfo::setBookId)) {
            return false;
        }

        if (!normalizeAndMatchId(chapterInfo.getChapterId(), normalizedChapterId, chapterInfo::setChapterId)) {
            return false;
        }

        return true;
    }

    private static String normalizeRequiredId(String id) {
        String normalized = Texts.trimToEmpty(id);
        if (!Texts.hasText(normalized)) {
            return null;
        }
        return normalized;
    }

    private static boolean normalizeAndMatchId(String currentValue, String expectedValue, Consumer<String> setter) {
        if (!Texts.hasText(expectedValue) || setter == null) {
            return false;
        }
        if (!Texts.hasText(currentValue)) {
            setter.accept(expectedValue);
            return true;
        }
        return expectedValue.equals(Texts.trimToEmpty(currentValue));
    }

    private static boolean isContentValid(FQNovelChapterInfo chapterInfo) {
        return chapterInfo != null
            && Texts.hasText(chapterInfo.getTitle())
            && Texts.hasText(chapterInfo.getTxtContent());
    }
}
