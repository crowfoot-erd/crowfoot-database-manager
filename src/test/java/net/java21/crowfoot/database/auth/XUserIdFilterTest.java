package net.java21.crowfoot.database.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * X-USER-ID 필터 — Gateway를 거치지 않은 요청은 401, 거친 요청은 사용자 컨텍스트를 얹는다.
 */
class XUserIdFilterTest {

    private final XUserIdFilter filter = new XUserIdFilter(JsonMapper.builder().build());

    private static MockHttpServletRequest post(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("헤더가 있으면 체인 안에서 사용자 컨텍스트가 보이고, 끝나면 지운다")
    void setsAndClearsCurrentUser() throws Exception {
        MockHttpServletRequest request = post("/database-manager/workspaces/34/connections/302/objects");
        request.addHeader(XUserIdFilter.USER_ID_HEADER, "1001");
        AtomicReference<CurrentUser> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(CurrentUserHolder.get());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo(new CurrentUser(1001L));
        assertThatThrownBy(CurrentUserHolder::get).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("헤더가 없거나 숫자가 아니면 401 AUTH_TOKEN_INVALID — 체인을 타지 않는다")
    void rejectsMissingOrMalformedHeader() throws Exception {
        for (String header : new String[] {null, " ", "abc"}) {
            MockHttpServletRequest request = post("/database-manager/workspaces/34/connections/302/objects");
            if (header != null) request.addHeader(XUserIdFilter.USER_ID_HEADER, header);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = (req, res) -> {
                throw new AssertionError("체인을 타면 안 된다");
            };

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("\"resultCode\":\"AUTH_TOKEN_INVALID\"");
        }
    }

    @Test
    @DisplayName("헬스체크는 헤더 없이 통과한다")
    void skipsActuator() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRequestURI("/actuator/health");
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));

        assertThat(called.get()).isTrue();
    }

    @Test
    @DisplayName("워크스페이스 액세스 토큰(MCP) — 그 워크스페이스의 샘플 데이터 넣기와 데이터 확인(v1.36)만 통과한다")
    void tokenRequestsReachOnlySampleData() throws Exception {
        MockHttpServletRequest allowed = post("/database-manager/workspaces/34/connections/302/sample-data");
        allowed.addHeader(XUserIdFilter.USER_ID_HEADER, "1001");
        allowed.addHeader(XUserIdFilter.TOKEN_WORKSPACE_HEADER, "34");
        allowed.addHeader(XUserIdFilter.TOKEN_ID_HEADER, "12");
        AtomicReference<CurrentUser> seen = new AtomicReference<>();
        filter.doFilter(allowed, new MockHttpServletResponse(), (req, res) -> seen.set(CurrentUserHolder.get()));
        assertThat(seen.get()).isEqualTo(new CurrentUser(1001L, 34L, "12"));
        assertThat(seen.get().viaToken()).isTrue();

        MockHttpServletRequest checks = post("/database-manager/workspaces/34/connections/302/checks");
        checks.addHeader(XUserIdFilter.USER_ID_HEADER, "1001");
        checks.addHeader(XUserIdFilter.TOKEN_WORKSPACE_HEADER, "34");
        AtomicReference<CurrentUser> checked = new AtomicReference<>();
        filter.doFilter(checks, new MockHttpServletResponse(), (req, res) -> checked.set(CurrentUserHolder.get()));
        assertThat(checked.get()).isNotNull();

        for (String uri : new String[] {
                "/database-manager/workspaces/35/connections/302/sample-data",   // 다른 워크스페이스
                "/database-manager/workspaces/35/connections/302/checks",        // 다른 워크스페이스의 데이터 확인
                "/database-manager/workspaces/34/connections/302/objects",        // 데이터 조회
                "/database-manager/workspaces/34/connections/302/queries",        // SQL 콘솔
                "/database-manager/workspaces/34/connections/302/objects/users/changes"}) {
            MockHttpServletRequest denied = post(uri);
            denied.addHeader(XUserIdFilter.USER_ID_HEADER, "1001");
            denied.addHeader(XUserIdFilter.TOKEN_WORKSPACE_HEADER, "34");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(denied, response, (req, res) -> {
                throw new AssertionError("체인을 타면 안 된다: " + uri);
            });
            assertThat(response.getStatus()).as(uri).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("\"resultCode\":\"PERMISSION_DENIED\"");
        }
    }
}
