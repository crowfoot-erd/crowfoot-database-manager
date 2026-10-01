package net.java21.crowfoot.database.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 한도 (09-database-manager/00-data-browser.md Section 2.3) — 값은 application.yml의 crowfoot.database.limits.
 *
 * @param connectTimeout     접속 제한 시간
 * @param statementTimeout   문장 실행 제한 시간 — gateway 응답 제한(10초)보다 짧아야 한다
 * @param pageSizeDefault    행 조회 페이지 크기 기본값
 * @param pageSizeMax        행 조회 페이지 크기 상한
 * @param consoleRowsDefault 콘솔 결과 행 수 기본값
 * @param consoleRowsMax     콘솔 결과 행 수 상한
 * @param cellTextLength     문자 셀을 자르는 길이
 * @param valueLengthMax     긴 값 하나의 길이 상한(읽기·쓰기)
 * @param responseBytesMax   응답 크기 상한
 * @param filtersMax         필터 조건 수 상한
 * @param sortsMax           정렬 컬럼 수 상한
 * @param changesMax         한 번에 적용하는 변경 수 상한
 * @param sqlLengthMax       콘솔 SQL 길이 상한
 * @param concurrentPerUser  사용자당 동시 실행 수
 */
@ConfigurationProperties(prefix = "crowfoot.database.limits")
public record LimitsProperties(
        Duration connectTimeout,
        Duration statementTimeout,
        int pageSizeDefault,
        int pageSizeMax,
        int consoleRowsDefault,
        int consoleRowsMax,
        int cellTextLength,
        int valueLengthMax,
        int responseBytesMax,
        int filtersMax,
        int sortsMax,
        int changesMax,
        int sqlLengthMax,
        int concurrentPerUser
) {
}
