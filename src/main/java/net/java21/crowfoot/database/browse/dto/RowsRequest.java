package net.java21.crowfoot.database.browse.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 행 조회 요청 (00-data-browser.md Section 3.3). 한도(페이지 크기·조건 수·정렬 수)는 서비스가 설정값으로 검사한다.
 *
 * @param page    1부터. 생략하면 1
 * @param size    생략하면 기본 페이지 크기
 * @param filters 조건 목록 — 모두 AND로 묶는다
 * @param sort    정렬 목록 — 없으면 기본 키 오름차순
 */
public record RowsRequest(
        @Min(1) Integer page,
        @Min(1) Integer size,
        @Valid List<Filter> filters,
        @Valid List<Sort> sort
) {

    /**
     * @param value 문자열(비교·LIKE), 문자열 배열(IN), 없음(IS_NULL·IS_NOT_NULL)
     */
    public record Filter(@NotBlank String column, @NotNull Op op, Object value) {
    }

    public record Sort(@NotBlank String column, Direction direction) {
    }

    public enum Op {
        EQ("="), NEQ("<>"), LT("<"), LTE("<="), GT(">"), GTE(">="),
        CONTAINS(null), STARTS_WITH(null), IN(null), IS_NULL(null), IS_NOT_NULL(null);

        private final String comparison;

        Op(String comparison) {
            this.comparison = comparison;
        }

        /** 단순 비교 연산자의 SQL 표기 — 그 밖의 연산자는 null */
        public String comparison() {
            return comparison;
        }
    }

    public enum Direction {
        ASC, DESC
    }
}
