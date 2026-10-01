package net.java21.crowfoot.database.browse.dto;

import net.java21.crowfoot.database.jdbc.ColumnMeta;

import java.util.List;

/**
 * 행 조회 응답 (00-data-browser.md Section 3.3).
 *
 * @param rows      컬럼 순서에 맞춘 배열 — 셀 값 표기는 Section 2.2
 * @param hasNext   다음 페이지가 있는지 — 전체 행 수는 세지 않는다
 * @param truncated 응답 크기 한도 때문에 이 페이지의 행을 줄였는지
 */
public record RowsResponse(List<ColumnMeta> columns, List<List<Object>> rows, int page, int size,
                           boolean hasNext, boolean truncated, long elapsedMs) {
}
