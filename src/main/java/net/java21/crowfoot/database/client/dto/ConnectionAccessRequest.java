package net.java21.crowfoot.database.client.dto;

/**
 * core 접근 확인 요청 (08-core/15-internal-api.md Section 2.1).
 *
 * @param userId      요청한 사용자 — Gateway가 붙인 X-USER-ID 값
 * @param workspaceId 요청 경로의 워크스페이스
 */
public record ConnectionAccessRequest(String userId, String workspaceId) {
}
