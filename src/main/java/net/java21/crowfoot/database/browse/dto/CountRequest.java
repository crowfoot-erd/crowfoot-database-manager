package net.java21.crowfoot.database.browse.dto;

import jakarta.validation.Valid;

import java.util.List;

/**
 * 정확한 행 수 요청 (00-data-browser.md Section 3.4) — 조건 형식은 행 조회와 같다.
 */
public record CountRequest(@Valid List<RowsRequest.Filter> filters) {
}
