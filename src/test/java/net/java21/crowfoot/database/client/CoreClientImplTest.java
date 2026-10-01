package net.java21.crowfoot.database.client;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.client.dto.CreateAuditLogRequest;
import net.java21.crowfoot.database.common.ApiResponse;
import net.java21.crowfoot.database.common.ResponseHeader;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * core 내부 API 창구 — 접근 판정 전달과 fail-closed 변환 (08-core/15-internal-api.md Section 2.1).
 */
class CoreClientImplTest {

    private final CoreFeignClient coreFeign = mock(CoreFeignClient.class);
    private final CoreClientImpl client = new CoreClientImpl(coreFeign, JsonMapper.builder().build());

    private static final ConnectionAccess ACCESS = new ConnectionAccess(
            "302", "개발 MySQL", "34", "EDITOR", "mysql", "db.dev.example.com", 3306, "shop", null, "crowfoot", "s3cret");

    private static FeignException coreError(int status, String resultCode) {
        String body = "{\"header\":{\"isSuccessful\":false,\"resultCode\":\"" + resultCode + "\",\"resultMessage\":\"x\"}}";
        Request request = Request.create(Request.HttpMethod.POST, "http://core/internal/core/connections/302/access",
                Map.of(), null, StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("access", Response.builder()
                .status(status).reason("x").request(request).headers(Map.of())
                .body(body, StandardCharsets.UTF_8).build());
    }

    @Test
    @DisplayName("통과 — core가 준 접속 정보를 그대로 돌려준다")
    void returnsAccess() {
        when(coreFeign.access(eq("302"), any())).thenReturn(new ApiResponse<>(ResponseHeader.success(), ACCESS));

        ConnectionAccess access = client.requireAccess(7L, "34", "302");

        assertThat(access.host()).isEqualTo("db.dev.example.com");
        assertThat(access.password()).isEqualTo("s3cret");
    }

    @ParameterizedTest(name = "core {0} {1} → 같은 코드로 전달")
    @CsvSource({"404,WORKSPACE_NOT_FOUND", "404,CONNECTION_NOT_FOUND", "403,PERMISSION_DENIED"})
    void passesThroughAccessVerdicts(int status, String resultCode) {
        when(coreFeign.access(any(), any())).thenThrow(coreError(status, resultCode));

        assertThatThrownBy(() -> client.requireAccess(7L, "34", "302"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.valueOf(resultCode)));
    }

    @ParameterizedTest(name = "core {0} {1} → SERVICE_UNAVAILABLE(fail-closed)")
    @CsvSource({"500,INTERNAL_ERROR", "404,SOMETHING_ELSE", "409,PERMISSION_DENIED", "503,SERVICE_UNAVAILABLE"})
    void failsClosedOnAnythingElse(int status, String resultCode) {
        when(coreFeign.access(any(), any())).thenThrow(coreError(status, resultCode));

        assertThatThrownBy(() -> client.requireAccess(7L, "34", "302"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("2xx인데 본문이 계약 밖(response 없음)이면 SERVICE_UNAVAILABLE")
    void failsClosedOnEmptyBody() {
        when(coreFeign.access(any(), any())).thenReturn(new ApiResponse<>(ResponseHeader.success(), null));

        assertThatThrownBy(() -> client.requireAccess(7L, "34", "302"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("감사 기록은 best-effort — core가 실패해도 예외를 던지지 않는다")
    void auditIsBestEffort() {
        when(coreFeign.recordAudit(any())).thenThrow(coreError(500, "INTERNAL_ERROR"));

        assertThatCode(() -> client.recordAuditLog(7L, "CONNECTION_DATA_ACCESSED", "{}")).doesNotThrowAnyException();
        verify(coreFeign).recordAudit(new CreateAuditLogRequest("7", "CONNECTION_DATA_ACCESSED", "{}"));
    }

    @Test
    @DisplayName("접속 정보의 toString은 비밀번호를 찍지 않는다")
    void accessToStringHidesPassword() {
        assertThat(ACCESS.toString()).doesNotContain("s3cret").contains("password=***");
    }
}
