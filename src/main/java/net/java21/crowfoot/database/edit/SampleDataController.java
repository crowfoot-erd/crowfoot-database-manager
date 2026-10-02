package net.java21.crowfoot.database.edit;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.auth.CurrentUser;
import net.java21.crowfoot.database.auth.CurrentUserHolder;
import net.java21.crowfoot.database.common.ApiResponse;
import net.java21.crowfoot.database.edit.dto.SampleDataRequest;
import net.java21.crowfoot.database.edit.dto.SampleDataResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 샘플 데이터 넣기 API (00-data-browser.md Section 3.8) — 외부 경로는 /api/v1/database-manager/**.
 * MCP 서버가 워크스페이스 액세스 토큰의 헤더를 붙여 부른다. 웹 토큰으로도 부를 수 있다(Editor 이상).
 */
@RestController
@RequiredArgsConstructor
public class SampleDataController {

    private final EditService editService;

    @PostMapping("/database-manager/workspaces/{workspaceId}/connections/{connectionId}/sample-data")
    public ApiResponse<SampleDataResponse> sampleData(@PathVariable("workspaceId") String workspaceId,
                                                      @PathVariable("connectionId") String connectionId,
                                                      @Valid @RequestBody SampleDataRequest request) {
        CurrentUser current = CurrentUserHolder.get();
        return ApiResponse.success(editService.sampleData(
                current.userId(), workspaceId, connectionId, request, current.viaToken(), current.tokenId()));
    }
}
