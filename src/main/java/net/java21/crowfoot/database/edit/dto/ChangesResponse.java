package net.java21.crowfoot.database.edit.dto;

import java.util.List;
import java.util.Map;

/**
 * 행 편집 적용 결과 (00-data-browser.md Section 3.5).
 *
 * @param generatedKeys 추가한 행의 기본 키 — index는 요청의 변경 번호(0부터)
 */
public record ChangesResponse(int inserted, int updated, int deleted, List<GeneratedKey> generatedKeys, long elapsedMs) {

    public record GeneratedKey(int index, Map<String, String> key) {
    }
}
