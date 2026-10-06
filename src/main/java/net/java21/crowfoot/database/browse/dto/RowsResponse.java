package net.java21.crowfoot.database.browse.dto;

import net.java21.crowfoot.database.jdbc.ColumnMeta;

import java.util.List;
import java.util.Map;

/**
 * 행 조회 응답 (00-data-browser.md Section 3.3·5.11).
 *
 * @param rows        컬럼 순서에 맞춘 배열 — 셀 값 표기는 Section 2.2
 * @param hasNext     다음 페이지가 있는지 — 전체 행 수는 세지 않는다
 * @param truncated   응답 크기 한도 때문에 이 페이지의 행을 줄였는지
 * @param hasPrevious 이전 페이지가 있는지(v1.36)
 * @param keyset      이 정렬에서 키 기준 페이지 넘김(after·before)을 쓸 수 있는지(v1.36)
 * @param firstKey    keyset일 때 이 페이지 첫 행의 기본 키 값 — 이전 페이지 요청의 before. 행이 없으면 null
 * @param lastKey     keyset일 때 이 페이지 마지막 행의 기본 키 값 — 다음 페이지 요청의 after. 행이 없으면 null
 */
public record RowsResponse(List<ColumnMeta> columns, List<List<Object>> rows, int page, int size,
                           boolean hasNext, boolean truncated, long elapsedMs,
                           boolean hasPrevious, boolean keyset,
                           Map<String, String> firstKey, Map<String, String> lastKey) {
}
