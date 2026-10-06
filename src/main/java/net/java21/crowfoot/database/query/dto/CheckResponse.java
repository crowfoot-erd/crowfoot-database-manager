package net.java21.crowfoot.database.query.dto;

import java.util.List;

/**
 * 데이터 확인 응답 (00-data-browser.md Section 3.9 — v1.36).
 *
 * @param results 요청 순서대로. status는 PASSED·FAILED·ERROR, value는 첫 행 첫 열(행이 없으면 null).
 *                ERROR일 때 errorCode는 MULTIPLE_STATEMENTS·UNSUPPORTED_STATEMENT·NO_RESULT·QUERY_TIMEOUT·QUERY_FAILED,
 *                message는 QUERY_FAILED일 때 데이터베이스의 오류 문구다
 */
public record CheckResponse(List<Result> results, int passed, int failed, int errors, long elapsedMs) {

    public record Result(String key, String status, String value, String expect, String errorCode, String message,
                         long elapsedMs) {
    }
}
