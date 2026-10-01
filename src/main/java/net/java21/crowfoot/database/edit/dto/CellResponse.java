package net.java21.crowfoot.database.edit.dto;

/**
 * 긴 값 읽기 응답 (00-data-browser.md Section 3.7) — 값이 NULL이면 value가 null이고 length는 0이다.
 */
public record CellResponse(String column, String value, long length, long elapsedMs) {
}
