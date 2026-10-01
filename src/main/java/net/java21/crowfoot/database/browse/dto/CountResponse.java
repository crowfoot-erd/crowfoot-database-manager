package net.java21.crowfoot.database.browse.dto;

/**
 * 정확한 행 수 응답 (00-data-browser.md Section 3.4) — 수도 문자열이다(Section 2.2).
 */
public record CountResponse(String count, long elapsedMs) {
}
