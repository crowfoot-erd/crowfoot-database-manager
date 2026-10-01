package net.java21.crowfoot.database.browse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.auth.CurrentUserHolder;
import net.java21.crowfoot.database.browse.dto.CountRequest;
import net.java21.crowfoot.database.browse.dto.CountResponse;
import net.java21.crowfoot.database.browse.dto.ObjectsResponse;
import net.java21.crowfoot.database.browse.dto.RowsRequest;
import net.java21.crowfoot.database.browse.dto.RowsResponse;
import net.java21.crowfoot.database.browse.dto.StructureResponse;
import net.java21.crowfoot.database.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 데이터 조회 API (00-data-browser.md Section 3.1~3.4) — 외부 경로는 /api/v1/database-manager/**.
 * 조회도 POST다 — 자격 증명과 비용이 따르는 경로이고 응답을 캐시하면 안 된다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/database-manager/workspaces/{workspaceId}/connections/{connectionId}")
public class BrowseController {

    private final BrowseService browseService;

    @PostMapping("/objects")
    public ApiResponse<ObjectsResponse> objects(@PathVariable("workspaceId") String workspaceId,
                                                @PathVariable("connectionId") String connectionId) {
        return ApiResponse.success(browseService.objects(userId(), workspaceId, connectionId));
    }

    @PostMapping("/objects/{objectName}/structure")
    public ApiResponse<StructureResponse> structure(@PathVariable("workspaceId") String workspaceId,
                                                    @PathVariable("connectionId") String connectionId,
                                                    @PathVariable("objectName") String objectName) {
        return ApiResponse.success(browseService.structure(userId(), workspaceId, connectionId, objectName));
    }

    @PostMapping("/objects/{objectName}/rows")
    public ApiResponse<RowsResponse> rows(@PathVariable("workspaceId") String workspaceId,
                                          @PathVariable("connectionId") String connectionId,
                                          @PathVariable("objectName") String objectName,
                                          @Valid @RequestBody(required = false) RowsRequest request) {
        return ApiResponse.success(browseService.rows(userId(), workspaceId, connectionId, objectName, request));
    }

    @PostMapping("/objects/{objectName}/count")
    public ApiResponse<CountResponse> count(@PathVariable("workspaceId") String workspaceId,
                                            @PathVariable("connectionId") String connectionId,
                                            @PathVariable("objectName") String objectName,
                                            @Valid @RequestBody(required = false) CountRequest request) {
        return ApiResponse.success(browseService.count(userId(), workspaceId, connectionId, objectName, request));
    }

    private static long userId() {
        return CurrentUserHolder.get().userId();
    }
}
