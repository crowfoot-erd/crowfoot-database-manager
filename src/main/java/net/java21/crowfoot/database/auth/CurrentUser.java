package net.java21.crowfoot.database.auth;

/**
 * 인증된 요청 사용자 — Gateway가 Introspection 검증 후 주입한 X-USER-ID(JWT sub 문자열) 원천.
 * (core와 같은 방식 — 이 서버는 인증 코드를 갖지 않고 이 헤더를 신뢰한다)
 */
public record CurrentUser(long userId, Long tokenWorkspaceId, String tokenId) {

    /** 웹에서 로그인한 사용자의 요청 */
    public CurrentUser(long userId) {
        this(userId, null, null);
    }

    /** 워크스페이스 액세스 토큰(MCP)으로 온 요청인가 — Gateway가 X-TOKEN-WORKSPACE-ID를 넣는다 */
    public boolean viaToken() {
        return tokenWorkspaceId != null;
    }


    public String userIdString() {
        return Long.toString(userId);
    }
}
