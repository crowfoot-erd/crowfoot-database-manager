package net.java21.crowfoot.database.query;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.auth.CurrentUserHolder;
import net.java21.crowfoot.database.common.ApiResponse;
import net.java21.crowfoot.database.query.dto.CheckRequest;
import net.java21.crowfoot.database.query.dto.CheckResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 데이터 확인 API (00-data-browser.md Section 3.9 — v1.36) — 요구사항 수용 기준의 확인 SQL을 읽기 전용으로 실행한다.
 */
@RestController
@RequiredArgsConstructor
public class CheckController {

    private final CheckService checkService;

    @PostMapping("/database-manager/workspaces/{workspaceId}/connections/{connectionId}/checks")
    public ApiResponse<CheckResponse> run(@PathVariable("workspaceId") String workspaceId,
                                          @PathVariable("connectionId") String connectionId,
                                          @RequestBody CheckRequest request) {
        return ApiResponse.success(checkService.run(CurrentUserHolder.get().userId(), workspaceId, connectionId, request));
    }
}
