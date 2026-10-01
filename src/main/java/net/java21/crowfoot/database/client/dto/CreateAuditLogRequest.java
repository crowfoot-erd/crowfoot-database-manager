package net.java21.crowfoot.database.client.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * core 감사 기록 요청 (08-core/15-internal-api.md Section 1.5) — 기록은 best-effort.
 * detail은 core가 {"raw": ...}로 감싸 저장한다.
 */
public record CreateAuditLogRequest(
        String actorId,
        @NotBlank String action,
        String detail
) {
}
