package net.java21.crowfoot.database.edit;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.auth.CurrentUserHolder;
import net.java21.crowfoot.database.common.ApiResponse;
import net.java21.crowfoot.database.edit.dto.CellRequest;
import net.java21.crowfoot.database.edit.dto.CellResponse;
import net.java21.crowfoot.database.edit.dto.ChangesRequest;
import net.java21.crowfoot.database.edit.dto.ChangesResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 행 편집·긴 값 읽기 API (00-data-browser.md Section 3.5·3.7) — 외부 경로는 /api/v1/database-manager/**.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/database-manager/workspaces/{workspaceId}/connections/{connectionId}/objects/{objectName}")
public class EditController {

    private final EditService editService;

    @PostMapping("/changes")
    public ApiResponse<ChangesResponse> changes(@PathVariable("workspaceId") String workspaceId,
                                                @PathVariable("connectionId") String connectionId,
                                                @PathVariable("objectName") String objectName,
                                                @Valid @RequestBody ChangesRequest request) {
        return ApiResponse.success(editService.apply(
                CurrentUserHolder.get().userId(), workspaceId, connectionId, objectName, request));
    }

    @PostMapping("/cell")
    public ApiResponse<CellResponse> cell(@PathVariable("workspaceId") String workspaceId,
                                          @PathVariable("connectionId") String connectionId,
                                          @PathVariable("objectName") String objectName,
                                          @Valid @RequestBody CellRequest request) {
        return ApiResponse.success(editService.cell(
                CurrentUserHolder.get().userId(), workspaceId, connectionId, objectName, request));
    }
}
