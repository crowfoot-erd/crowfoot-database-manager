package net.java21.crowfoot.database.query;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.auth.CurrentUserHolder;
import net.java21.crowfoot.database.common.ApiResponse;
import net.java21.crowfoot.database.query.dto.QueryRequest;
import net.java21.crowfoot.database.query.dto.QueryResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SQL 콘솔 API (00-data-browser.md Section 3.6) — 외부 경로는 /api/v1/database-manager/**.
 */
@RestController
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;

    @PostMapping("/database-manager/workspaces/{workspaceId}/connections/{connectionId}/queries")
    public ApiResponse<QueryResponse> execute(@PathVariable("workspaceId") String workspaceId,
                                              @PathVariable("connectionId") String connectionId,
                                              @Valid @RequestBody QueryRequest request) {
        return ApiResponse.success(queryService.execute(
                CurrentUserHolder.get().userId(), workspaceId, connectionId, request));
    }
}
