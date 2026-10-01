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
 * <p>구현 경로 {@code /database/**}는 Gateway가 주입한 X-USER-ID(sub 문자열)을 요구한다 —
 * 헤더 없는 요청은 Gateway를 거치지 않은 요청이므로 401로 거부한다(공통 실패 포맷).
 * 이 서버에는 공개 경로가 없다. 제외는 {@code /actuator/**}(헬스체크)와 CORS preflight뿐이다.
 */
@Component
@RequiredArgsConstructor
public class XUserIdFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-USER-ID";

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/database")
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
        try {
            CurrentUserHolder.set(new CurrentUser(userId));
            filterChain.doFilter(request, response);
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.AUTH_TOKEN_INVALID;
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ErrorResponse.of(code.getCode(), code.getDefaultMessage())));
    }
}
