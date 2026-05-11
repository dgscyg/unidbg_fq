package com.mengying.fqnovel.dto;

import java.util.List;

/**
 * 批量章节请求。
 */
public class FQBatchChaptersRequest {

    private String bookId;
    private List<String> chapterIds;
    private Boolean includeRawContent = false;

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
    }

    public List<String> getChapterIds() {
        return chapterIds;
    }

    public void setChapterIds(List<String> chapterIds) {
        this.chapterIds = chapterIds;
    }

    public Boolean getIncludeRawContent() {
        return includeRawContent;
    }

    public void setIncludeRawContent(Boolean includeRawContent) {
        this.includeRawContent = includeRawContent;
    }
}