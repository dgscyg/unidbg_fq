package com.mengying.fqnovel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 批量章节响应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FQBatchChaptersResponse(
    String bookId,
    Integer requestedCount,
    Integer returnedCount,
    Boolean partial,
    List<String> missingChapterIds,
    List<FQNovelChapterInfo> chapters
) {
}