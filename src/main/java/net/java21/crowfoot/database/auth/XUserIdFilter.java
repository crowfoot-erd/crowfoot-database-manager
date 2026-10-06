package net.java21.crowfoot.database.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.common.ErrorResponse;
import net.java21.crowfoot.database.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * X-USER-ID 검증 필터 (09-database-manager/00-data-browser.md Section 1.5 — core XUserIdFilter와 같은 방식).
 *
 * <p>구현 경로 {@code /database-manager/**}는 Gateway가 주입한 X-USER-ID(sub 문자열)을 요구한다 —
 * 헤더 없는 요청은 Gateway를 거치지 않은 요청이므로 401로 거부한다(공통 실패 포맷).
 * 이 서버에는 공개 경로가 없다. 제외는 {@code /actuator/**}(헬스체크)와 CORS preflight뿐이다.
 */
@Component
@RequiredArgsConstructor
public class XUserIdFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-USER-ID";
    public static final String TOKEN_WORKSPACE_HEADER = "X-TOKEN-WORKSPACE-ID";
    public static final String TOKEN_ID_HEADER = "X-ACCESS-TOKEN-ID";
    /** 토큰으로 온 요청이 부를 수 있는 경로 — 샘플 데이터(3.8), 데이터 확인(3.9 — v1.36, 읽기 전용) */
    private static final java.util.regex.Pattern TOKEN_ALLOWED =
            java.util.regex.Pattern.compile("^/database-manager/workspaces/\\d+/connections/\\d+/(sample-data|checks)$");

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/database-manager")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            writeUnauthorized(response);
            return;
        }
        long userId;
        try {
            userId = Long.parseLong(header.trim());
        } catch (NumberFormatException ex) {
            writeUnauthorized(response);
            return;
        }
        // 워크스페이스 액세스 토큰(MCP)으로 온 요청 — 그 워크스페이스의 샘플 데이터 넣기만 부를 수 있다
        // (00-data-browser.md Section 3.8). 데이터 조회·행 편집·SQL 콘솔은 403이다
        Long tokenWorkspaceId = null;
        String tokenWorkspace = request.getHeader(TOKEN_WORKSPACE_HEADER);
        if (tokenWorkspace != null && !tokenWorkspace.isBlank()) {
            try {
                tokenWorkspaceId = Long.parseLong(tokenWorkspace.trim());
            } catch (NumberFormatException ex) {
                writeError(response, ErrorCode.PERMISSION_DENIED);
                return;
            }
            if (!TOKEN_ALLOWED.matcher(request.getRequestURI()).matches()
                    || !request.getRequestURI().startsWith("/database-manager/workspaces/" + tokenWorkspaceId + "/")) {
                writeError(response, ErrorCode.PERMISSION_DENIED);
                return;
            }
        }
        String tokenId = request.getHeader(TOKEN_ID_HEADER);
        try {
            CurrentUserHolder.set(new CurrentUser(userId, tokenWorkspaceId, tokenId == null || tokenId.isBlank() ? null : tokenId.trim()));
            filterChain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        writeError(response, ErrorCode.AUTH_TOKEN_INVALID);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ErrorResponse.of(code.getCode(), code.getDefaultMessage())));
    }
}
