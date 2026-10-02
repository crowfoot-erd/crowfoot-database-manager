package net.java21.crowfoot.database.edit.dto;

import java.util.List;

/**
 * 샘플 데이터 넣기 결과 (00-data-browser.md Section 3.8).
 *
 * @param dryRun   넣어 본 뒤 되돌렸으면 true — 남은 행이 없다
 * @param inserted 넣은 행 수(모든 테이블 합)
 */
public record SampleDataResponse(boolean dryRun, int inserted, List<TableCount> tables, long elapsedMs) {

    public record TableCount(String name, int inserted) {
    }
}
