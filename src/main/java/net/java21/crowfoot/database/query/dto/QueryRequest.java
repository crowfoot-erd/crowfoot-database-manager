package net.java21.crowfoot.database.query.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * SQL 콘솔 실행 요청 (00-data-browser.md Section 3.6). 길이·행 수 한도는 서비스가 설정값으로 검사한다.
 *
 * @param sql       실행할 문장 — 한 문장
 * @param maxRows   결과 행 상한. 생략하면 기본값
 * @param confirmed 쓰기·구조 문장을 사용자가 확인했는지. 생략하면 false
 */
public record QueryRequest(@NotBlank String sql, @Min(1) Integer maxRows, Boolean confirmed) {
}
