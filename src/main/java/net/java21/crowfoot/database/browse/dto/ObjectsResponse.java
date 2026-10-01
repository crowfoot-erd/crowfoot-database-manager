package net.java21.crowfoot.database.browse.dto;

import java.util.List;

/**
 * 객체 목록 응답 (00-data-browser.md Section 3.1).
 */
public record ObjectsResponse(String dbmsType, String schema, List<Item> objects) {

    /**
     * @param kind          {@code TABLE}·{@code VIEW}
     * @param estimatedRows 카탈로그 통계의 추정 행 수 — 정확한 수가 아니다. 통계가 없으면 null
     * @param editable      행 편집을 할 수 있는지 — 기본 키가 있는 테이블만 true
     */
    public record Item(String name, String kind, Long estimatedRows, boolean editable, String comment) {
    }
}
