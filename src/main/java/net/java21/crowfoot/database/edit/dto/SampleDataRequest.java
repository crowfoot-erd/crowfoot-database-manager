package net.java21.crowfoot.database.edit.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/**
 * 샘플 데이터 넣기 요청 (00-data-browser.md Section 3.8) — 요청 하나가 트랜잭션 하나다. 넣기만 한다.
 *
 * @param dryRun true면 실제로 넣어 본 뒤 전부 되돌린다(제약 위반·타입 오류를 미리 본다)
 * @param tables 대상 테이블과 행 — 적힌 순서대로 넣는다(부모 테이블을 먼저 적는다)
 */
public record SampleDataRequest(Boolean dryRun, @NotEmpty @Valid List<Table> tables) {

    /**
     * @param name 테이블 이름
     * @param rows 넣을 행 — 컬럼 이름과 값(문자열·숫자·불리언·null)
     */
    public record Table(@NotBlank String name, @NotEmpty List<Map<String, Object>> rows) {
    }
}
