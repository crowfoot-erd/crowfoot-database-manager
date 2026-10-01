package net.java21.crowfoot.database.query;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.client.CoreClient;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import net.java21.crowfoot.database.jdbc.CellValues;
import net.java21.crowfoot.database.jdbc.ColumnMeta;
import net.java21.crowfoot.database.jdbc.SqlErrors;
import net.java21.crowfoot.database.jdbc.SqlTypes;
import net.java21.crowfoot.database.jdbc.TargetDatabase;
import net.java21.crowfoot.database.query.dto.QueryRequest;
import net.java21.crowfoot.database.query.dto.QueryResponse;
import net.java21.crowfoot.database.support.UserConcurrencyLimiter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 콘솔 — 사용자가 쓴 SQL 한 문장을 실행한다 (00-data-browser.md Section 3.6).
 *
 * <p>사용자가 쓴 SQL을 그대로 실행하는 경로는 이 서비스 하나뿐이다(Section 1.2 원칙 5).
 * 읽기 문장은 읽기 전용 트랜잭션에서, 쓰기·구조 문장은 사용자 확인을 거쳐 자동 커밋으로 실행한다.
 * 데이터베이스가 문장을 거부하면 예외가 아니라 {@code ok:false} 결과로 돌려준다 —
 * 사용자가 쓴 SQL의 오류는 계약상 정상 응답이다. SQL 본문과 결과 값은 로그·감사 기록에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class QueryService {

    static final String ACTION_QUERY_EXECUTED = "CONNECTION_QUERY_EXECUTED";

    private final CoreClient coreClient;
    private final TargetDatabase targetDatabase;
    private final UserConcurrencyLimiter limiter;
    private final LimitsProperties limits;
    private final ObjectMapper objectMapper;

    public QueryResponse execute(long userId, String workspaceId, String connectionId, QueryRequest request) {
        String sql = request.sql();
        if (sql.length() > limits.sqlLengthMax()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "sql");
        }
        int maxRows = request.maxRows() == null ? limits.consoleRowsDefault() : request.maxRows();
        if (maxRows > limits.consoleRowsMax()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "maxRows");
        }
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId);
        boolean mysql = "mysql".equals(targetDatabase.dialectOf(access).dbmsType());

        SqlScanner.Scan scan = SqlScanner.scan(sql, mysql);
        if (scan.statementCount() == 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (scan.statementCount() > 1) {
            throw new BusinessException(ErrorCode.MULTIPLE_STATEMENTS);
        }
        StatementKind kind = StatementKind.of(scan.firstKeyword());
        if (kind == StatementKind.UNSUPPORTED) {
            throw BusinessException.of(ErrorCode.UNSUPPORTED_STATEMENT, "detail.query.unsupported", scan.firstKeyword());
        }
        if (kind.requiresConfirmation() && !Boolean.TRUE.equals(request.confirmed())) {
            throw new ConfirmationRequiredException(kind);
        }

        QueryResponse response = limiter.run(userId, () -> run(access, kind, sql, maxRows));
        coreClient.recordAuditLog(userId, ACTION_QUERY_EXECUTED, auditDetail(access, kind, sql, response));
        return response;
    }

    private QueryResponse run(ConnectionAccess access, StatementKind kind, String sql, int maxRows) {
        long start = System.nanoTime();
        Connection connection = kind == StatementKind.READ
                ? targetDatabase.openReadOnly(access)
                : targetDatabase.openWritable(access);
        try (Statement statement = connection.createStatement()) {
            targetDatabase.applyStatementTimeout(statement);
            if (kind == StatementKind.READ) {
                statement.setMaxRows(maxRows + 1);              // 한 행 더 — 잘렸는지 판단한다
            }
            boolean hasResultSet = statement.execute(sql);
            if (kind == StatementKind.READ) {
                if (!hasResultSet) {
                    // 읽기로 분류됐지만 결과 집합이 없다 — 행을 돌려주지 않는 문장. 빈 결과로 답한다
                    return QueryResponse.read(List.of(), List.of(), false, elapsedMs(start));
                }
                try (ResultSet rs = statement.getResultSet()) {
                    return readResult(rs, maxRows, start);
                }
            }
            long affected = hasResultSet ? -1 : statement.getLargeUpdateCount();
            return QueryResponse.changed(kind, kind == StatementKind.WRITE && affected >= 0 ? affected : null,
                    elapsedMs(start));
        } catch (SQLException e) {
            if (SqlErrors.isTimeout(e)) {
                throw new BusinessException(ErrorCode.QUERY_TIMEOUT);
            }
            // 데이터베이스가 문장을 거부했다 — 문구를 그대로 전달한다(사용자가 자기 SQL을 고치는 데 필요하다)
            return QueryResponse.failed(kind, SqlErrors.messageOf(e), e.getSQLState(), elapsedMs(start));
        } finally {
            TargetDatabase.closeQuietly(connection);
        }
    }

    private QueryResponse readResult(ResultSet rs, int maxRows, long start) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<ColumnMeta> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String rawType = meta.getColumnTypeName(i);
            int jdbcType = meta.getColumnType(i);
            columns.add(new ColumnMeta(meta.getColumnLabel(i),
                    SqlTypes.displayName(rawType, jdbcType, meta.getPrecision(i), meta.getScale(i)),
                    SqlTypes.category(rawType, jdbcType),
                    meta.isNullable(i) != ResultSetMetaData.columnNoNulls,
                    false));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        long bytes = 0;
        while (rs.next()) {
            if (rows.size() == maxRows) {
                truncated = true;                               // maxRows + 1번째 행 — 내보내지 않는다
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                Object cell = CellValues.read(rs, meta, i, limits.cellTextLength());
                bytes += CellValues.sizeOf(cell);
                row.add(cell);
            }
            if (bytes > limits.responseBytesMax() && !rows.isEmpty()) {
                truncated = true;                               // 응답 크기 한도(Section 2.3)
                break;
            }
            rows.add(row);
        }
        return QueryResponse.read(columns, rows, truncated, elapsedMs(start));
    }

    /** 감사 상세 — SQL 본문과 데이터 값은 넣지 않는다. 문장은 종류·길이·해시 앞 16자로만 남긴다(Section 2.5) */
    private String auditDetail(ConnectionAccess access, StatementKind kind, String sql, QueryResponse response) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workspaceId", access.workspaceId());
        detail.put("connectionId", access.connectionId());
        detail.put("kind", kind.name());
        detail.put("sqlLength", sql.length());
        detail.put("sqlHash", sha256Prefix(sql));
        // 삼항식으로 쓰지 않는다 — Integer와 Long이 섞이면 언박싱이 일어나 null에서 예외가 난다
        Object rows = response.rowCount();
        if (rows == null) {
            rows = response.affectedRows();
        }
        detail.put("rows", rows);
        detail.put("elapsedMs", response.elapsedMs());
        detail.put("ok", response.ok());
        return objectMapper.writeValueAsString(detail);
    }

    static String sha256Prefix(String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
