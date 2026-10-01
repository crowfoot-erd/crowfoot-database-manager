package net.java21.crowfoot.database.edit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/**
 * 긴 값 읽기 요청 (00-data-browser.md Section 3.7).
 *
 * @param key    대상 행의 기본 키 값
 * @param column 읽을 컬럼 이름
 */
public record CellRequest(@NotEmpty Map<String, Object> key, @NotBlank String column) {
}
