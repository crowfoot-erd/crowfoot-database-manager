package net.java21.crowfoot.database.query;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.client.CoreClient;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import net.java21.crowfoot.database.jdbc.SqlErrors;
import net.java21.crowfoot.database.jdbc.TargetDatabase;
import net.java21.crowfoot.database.query.dto.CheckRequest;
import net.java21.crowfoot.database.query.dto.CheckResponse;
import net.java21.crowfoot.database.support.UserConcurrencyLimiter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 데이터 확인 (00-data-browser.md Section 3.9 — v1.36). 요구사항 수용 기준의 확인 SQL을 읽기 전용 접속 하나에서 차례로 실행한다.
 *
 * <ul>
 *   <li>한 문장짜리 SELECT(읽기 분류 — SQL 콘솔과 같은 판정)만 실행한다. 다른 문장은 실행하지 않고 그 항목만 ERROR다</li>
 *   <li>접속은 읽기 전용 트랜잭션이고 끝나면 되돌린다. 문장마다 실행 제한 시간(8초)이 걸린다</li>
 *   <li>첫 행 첫 열을 기대값과 견준다. 둘 다 수면 수로, 아니면 앞뒤 공백을 뺀 글자로 견준다. 행이 없으면 값은 null이다</li>
 *   <li>한 항목의 실패가 다른 항목을 막지 않는다. 감사에는 건수만 남긴다(SQL과 값은 남기지 않는다)</li>
 * </ul>
 * 토큰(MCP) 요청도 받는다 — 읽기만 하므로 MCP 반영 허용과 무관하다.
 */
@Service
@RequiredArgsConstructor
public class CheckService {

    static final String ACTION_CHECKS_RUN = "CONNECTION_CHECKS_RUN";
    static final int CHECKS_MAX = 50;

    private final CoreClient coreClient;
    private final TargetDatabase targetDatabase;
    private final UserConcurrencyLimiter limiter;
    private final LimitsProperties limits;
    private final ObjectMapper objectMapper;

    public CheckResponse run(long userId, String workspaceId, String connectionId, CheckRequest request) {
        List<CheckRequest.Check> checks = request == null ? null : request.checks();
        if (checks == null || checks.isEmpty() || checks.size() > CHECKS_MAX) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "checks");
        }
        for (CheckRequest.Check check : checks) {
            if (check == null || check.sql() == null || check.sql().isBlank() || check.sql().length() > limits.sqlLengthMax()) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "checks[].sql");
            }
        }
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId);
        boolean mysql = "mysql".equals(targetDatabase.dialectOf(access).dbmsType());
        CheckResponse response = limiter.run(userId, () -> execute(access, checks, mysql));
        coreClient.recordAuditLog(userId, ACTION_CHECKS_RUN, auditDetail(access, response));
        return response;
    }

    private CheckResponse execute(ConnectionAccess access, List<CheckRequest.Check> checks, boolean mysql) {
        long start = System.nanoTime();
        List<CheckResponse.Result> results = new ArrayList<>();
        Connection connection = targetDatabase.openReadOnly(access);
        try {
            for (CheckRequest.Check check : checks) {
                results.add(one(connection, check, mysql));
            }
        } finally {
            rollbackQuietly(connection);
            TargetDatabase.closeQuietly(connection);
        }
        int passed = (int) results.stream().filter(r -> "PASSED".equals(r.status())).count();
        int failed = (int) results.stream().filter(r -> "FAILED".equals(r.status())).count();
        return new CheckResponse(results, passed, failed, results.size() - passed - failed, elapsedMs(start));
    }

    private CheckResponse.Result one(Connection connection, CheckRequest.Check check, boolean mysql) {
        long start = System.nanoTime();
        String expect = check.expect() == null || check.expect().isBlank() ? "0" : check.expect().strip();
        SqlScanner.Scan scan = SqlScanner.scan(check.sql(), mysql);
        if (scan.statementCount() != 1) {
            return error(check, expect, "MULTIPLE_STATEMENTS", null, start);
        }
        if (StatementKind.of(scan.firstKeyword()) != StatementKind.READ) {
            return error(check, expect, "UNSUPPORTED_STATEMENT", scan.firstKeyword(), start);
        }
        try (Statement statement = connection.createStatement()) {
            targetDatabase.applyStatementTimeout(statement);
            statement.setMaxRows(1);
            if (!statement.execute(check.sql())) {
                return error(check, expect, "NO_RESULT", null, start);
            }
            String value;
            try (ResultSet rs = statement.getResultSet()) {
                value = rs.next() ? rs.getString(1) : null;
            }
            String status = matches(value, expect) ? "PASSED" : "FAILED";
            return new CheckResponse.Result(check.key(), status, value, expect, null, null, elapsedMs(start));
        } catch (SQLException e) {
            // 실패한 문장은 PostgreSQL 트랜잭션을 멈춘다 — 되돌려 다음 항목을 이어서 실행한다
            rollbackQuietly(connection);
            return SqlErrors.isTimeout(e)
                    ? error(check, expect, "QUERY_TIMEOUT", null, start)
                    : error(check, expect, "QUERY_FAILED", SqlErrors.messageOf(e), start);
        }
    }

    /** 둘 다 수면 수로(0 = 0.00), 아니면 앞뒤 공백을 뺀 글자로 견준다. 값이 없으면(행 없음·NULL) 기대값이 NULL일 때만 맞다 */
    static boolean matches(String value, String expect) {
        if (value == null) {
            return "null".equalsIgnoreCase(expect);
        }
        String actual = value.strip();
        try {
            return new BigDecimal(actual).compareTo(new BigDecimal(expect)) == 0;
        } catch (NumberFormatException notNumber) {
            return actual.equals(expect);
        }
    }

    private static CheckResponse.Result error(CheckRequest.Check check, String expect, String code, String message, long start) {
        return new CheckResponse.Result(check.key(), "ERROR", null, expect, code, message, elapsedMs(start));
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 접속이 이미 끊겼다 — 닫기만 한다
        }
    }

    private String auditDetail(ConnectionAccess access, CheckResponse response) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workspaceId", access.workspaceId());
        detail.put("connectionId", access.connectionId());
        detail.put("checks", response.results().size());
        detail.put("passed", response.passed());
        detail.put("failed", response.failed());
        detail.put("errors", response.errors());
        detail.put("elapsedMs", response.elapsedMs());
        return objectMapper.writeValueAsString(detail);
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
