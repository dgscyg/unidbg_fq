package com.mengying.fqnovel.dto;

/**
 * FQNovel API 请求参数
 */
public class FQNovelRequest {
    
    /**
     * 书籍ID (获取章节内容时必须)
     */
    private String bookId;
    
    /**
     * 章节ID (获取章节内容时必须)
     */
    private String chapterId;

    private Boolean includeRawContent = false;

    private Boolean useHtmlStyle = false;

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
    }

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public Boolean getIncludeRawContent() {
        return includeRawContent;
    }

    public void setIncludeRawContent(Boolean includeRawContent) {
        this.includeRawContent = includeRawContent;
    }

    public Boolean getUseHtmlStyle() {
        return useHtmlStyle;
    }

    public void setUseHtmlStyle(Boolean useHtmlStyle) {
        this.useHtmlStyle = useHtmlStyle;
    }
}

