package net.java21.crowfoot.database.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 성공 — 목록 응답. (01-architecture/api-design.md Section 5.2 — core 미러)
 * 목록 응답 수신·송신에 쓴다.
 *
 * @param header      항상 존재
 * @param responses   항목 목록 — 값이 없어도 null이 아닌 빈 배열
 * @param totalCount  전체 항목 수
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListApiResponse<T>(
        ResponseHeader header,
        List<T> responses,
        long totalCount
) {

    public static <T> ListApiResponse<T> of(List<T> responses) {
        return new ListApiResponse<>(ResponseHeader.success(),
                responses == null ? List.of() : responses,
                responses == null ? 0 : responses.size());
    }
}
