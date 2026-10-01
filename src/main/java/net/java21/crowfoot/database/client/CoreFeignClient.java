package net.java21.crowfoot.database.client;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.client.dto.ConnectionAccessRequest;
import net.java21.crowfoot.database.client.dto.CreateAuditLogRequest;
import net.java21.crowfoot.database.common.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * core 내부 API 전송 계층 (08-core/15-internal-api.md Section 1.5·2.1) — 응답 해석·에러 매핑은 {@link CoreClientImpl}.
 */
@FeignClient(name = "crowfoot-core-api")
public interface CoreFeignClient {

    @PostMapping("/internal/core/connections/{connectionId}/access")
    ApiResponse<ConnectionAccess> access(@PathVariable("connectionId") String connectionId,
                                         @RequestBody ConnectionAccessRequest request);

    @PostMapping("/internal/core/audit-logs")
    ApiResponse<Void> recordAudit(@RequestBody CreateAuditLogRequest request);
}
