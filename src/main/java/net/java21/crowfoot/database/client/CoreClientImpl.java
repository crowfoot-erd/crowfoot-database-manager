package net.java21.crowfoot.database.client;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.client.dto.ConnectionAccessRequest;
import net.java21.crowfoot.database.client.dto.CreateAuditLogRequest;
import net.java21.crowfoot.database.common.ApiResponse;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * core 내부 API 호출 구현 — Feign 전송({@link CoreFeignClient})의 응답 해석·에러 매핑.
 *
 * <p>에러 변환: core의 접근 판정(403·404)은 본문 resultCode가 아래 표에 있을 때만 같은 코드로 전달하고,
 * 그 외(5xx·타임아웃·연결 거부·본문 파싱 실패·표에 없는 코드)는 fail-closed로 SERVICE_UNAVAILABLE을 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoreClientImpl implements CoreClient {

    /** core가 접근 확인에서 돌려주는 판정 코드 → 이 서버의 같은 이름 코드 (08-core/15-internal-api.md Section 2.1) */
    private static final Map<String, ErrorCode> ACCESS_VERDICTS = Map.of(
            "WORKSPACE_NOT_FOUND", ErrorCode.WORKSPACE_NOT_FOUND,
            "CONNECTION_NOT_FOUND", ErrorCode.CONNECTION_NOT_FOUND,
            "PERMISSION_DENIED", ErrorCode.PERMISSION_DENIED);

    private final CoreFeignClient coreFeignClient;
    private final ObjectMapper objectMapper;

    @Override
    public ConnectionAccess requireAccess(long userId, String workspaceId, String connectionId) {
        try {
            ApiResponse<ConnectionAccess> body = coreFeignClient.access(
                    connectionId, new ConnectionAccessRequest(Long.toString(userId), workspaceId));
            if (body == null || body.header() == null || !body.header().isSuccessful() || body.response() == null) {
                log.warn("core 접근 확인 응답이 계약 밖이다(connectionId={}) — SERVICE_UNAVAILABLE으로 변환", connectionId);
                throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "detail.core.unavailable");
            }
            return body.response();
        } catch (FeignException e) {
            throw translate(e);
        }
    }

    @Override
    public void recordAuditLog(long actorId, String action, String detail) {
        try {
            coreFeignClient.recordAudit(new CreateAuditLogRequest(Long.toString(actorId), action, detail));
        } catch (Exception e) {
            // best-effort — 감사 기록 실패가 본류(조회·편집·실행)의 응답을 바꾸지 않는다
            log.warn("감사 기록 실패(action={}, actorId={}) — best-effort 무시", action, actorId, e);
        }
    }

    /** core 403·404의 접근 판정은 같은 코드로 전달, 그 외·파싱 실패는 fail-closed 503 */
    private BusinessException translate(FeignException e) {
        int status = e.status();
        if (status == 403 || status == 404) {
            ErrorCode verdict = ACCESS_VERDICTS.get(readResultCode(e));
            if (verdict != null) {
                return new BusinessException(verdict);
            }
        }
        // 예외 객체를 통째로 찍지 않는다 — Feign 예외 메시지에 응답 본문이 실릴 수 있다
        log.warn("core 호출 실패(status={}) — SERVICE_UNAVAILABLE으로 변환", status);
        return BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "detail.core.unavailable");
    }

    private String readResultCode(FeignException e) {
        try {
            JsonNode root = objectMapper.readTree(e.contentUTF8());
            JsonNode code = root.path("header").path("resultCode");
            return code.isMissingNode() ? "" : code.asString();
        } catch (Exception parseEx) {
            return "";
        }
    }
}
