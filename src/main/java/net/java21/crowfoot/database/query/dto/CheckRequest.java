package net.java21.crowfoot.database.query.dto;

import java.util.List;

/**
 * 데이터 확인 요청 (00-data-browser.md Section 3.9 — v1.36). 요구사항 수용 기준의 확인 SQL을 한 번에 실행한다.
 *
 * @param checks 1~50개. key는 호출한 쪽이 결과를 맞출 값(수용 기준 id), sql은 SELECT 한 문장, expect는 기대값(생략하면 "0")
 */
public record CheckRequest(List<Check> checks) {

    public record Check(String key, String sql, String expect) {
    }
}
