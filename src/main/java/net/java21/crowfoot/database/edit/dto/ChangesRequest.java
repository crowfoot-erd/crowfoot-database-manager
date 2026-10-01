package net.java21.crowfoot.database.edit.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 행 편집 적용 요청 (00-data-browser.md Section 3.5) — 요청 하나가 트랜잭션 하나다.
 */
public record ChangesRequest(@NotEmpty @Valid List<Change> changes) {

    /**
     * @param key      대상 행의 기본 키 값 — UPDATE·DELETE에 필수
     * @param values   넣거나 바꿀 컬럼과 값(문자열 또는 null) — INSERT·UPDATE에 쓴다
     * @param original 바꾸려는 컬럼의 편집 전 값 — UPDATE의 충돌 검사에 쓴다
     */
    public record Change(@NotNull Op op, Map<String, Object> key, Map<String, Object> values,
                         Map<String, Object> original) {
    }

    public enum Op {
        INSERT, UPDATE, DELETE
    }
}
