package net.java21.crowfoot.database.query.dto;

import net.java21.crowfoot.database.jdbc.ColumnMeta;
import net.java21.crowfoot.database.query.StatementKind;

import java.util.List;

/**
 * SQL 콘솔 실행 결과 (00-data-browser.md Section 3.6) — 데이터베이스가 문장을 거부해도 200이고 ok가 false다.
 *
 * @param kind         READ·WRITE·DDL
 * @param columns      READ의 결과 컬럼 — 그 밖에는 null
 * @param rows         READ의 결과 행 — 셀 표기는 Section 2.2
 * @param rowCount     READ가 돌려준 행 수
 * @param truncated    행 수·응답 크기 한도 때문에 결과를 잘랐는지
 * @param affectedRows WRITE가 바꾼 행 수
 * @param error        문장이 거부됐을 때 데이터베이스의 문구
 */
public record QueryResponse(String kind, boolean ok, List<ColumnMeta> columns, List<List<Object>> rows,
                            Integer rowCount, boolean truncated, Long affectedRows, long elapsedMs, Error error) {

    public record Error(String message, String sqlState) {
    }

    public static QueryResponse read(List<ColumnMeta> columns, List<List<Object>> rows, boolean truncated, long elapsedMs) {
        return new QueryResponse("READ", true, columns, rows, rows.size(), truncated, null, elapsedMs, null);
    }

    public static QueryResponse changed(StatementKind kind, Long affectedRows, long elapsedMs) {
        return new QueryResponse(kind.name(), true, null, null, null, false, affectedRows, elapsedMs, null);
    }

    public static QueryResponse failed(StatementKind kind, String message, String sqlState, long elapsedMs) {
        return new QueryResponse(kind.name(), false, null, null, null, false, null, elapsedMs,
                new Error(message, sqlState));
    }
}
