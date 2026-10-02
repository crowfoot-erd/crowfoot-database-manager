package net.java21.crowfoot.database.client;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;

/**
 * core 내부 API 창구 — DB 매니저가 core에서 얻는 것은 이 둘뿐이다
 * (09-database-manager/00-data-browser.md Section 1.5).
 */
public interface CoreClient {

    /**
     * 접근 확인 — 사용자가 그 커넥션의 데이터를 다룰 수 있는지 core가 판정하고, 통과하면 접속 정보를 돌려준다.
     * 결과를 캐시하지 않는다(요청마다 호출 — 권한 회수가 바로 반영된다).
     *
     * @throws net.java21.crowfoot.database.common.error.BusinessException
     *         WORKSPACE_NOT_FOUND·CONNECTION_NOT_FOUND·PERMISSION_DENIED(core 판정 전달),
     *         SERVICE_UNAVAILABLE(core 무응답·계약 밖 응답)
     */
    ConnectionAccess requireAccess(long userId, String workspaceId, String connectionId);

    /**
     * 접근 확인 — MCP로 온 쓰기 요청이면 mcpWrite를 켠다. core가 MCP 반영을 허용한 커넥션인지까지 본다
     * (08-core/15-internal-api.md Section 2.1).
     */
    default ConnectionAccess requireAccess(long userId, String workspaceId, String connectionId, boolean mcpWrite) {
        return requireAccess(userId, workspaceId, connectionId);
    }

    /** 감사 기록 — best-effort. 실패해도 본류의 응답을 바꾸지 않는다 */
    void recordAuditLog(long actorId, String action, String detail);
}
