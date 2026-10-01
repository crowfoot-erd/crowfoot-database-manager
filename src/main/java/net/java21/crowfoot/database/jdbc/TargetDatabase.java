package net.java21.crowfoot.database.jdbc;

import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 대상 데이터베이스 접속 — 요청 하나가 접속 하나다(00-data-browser.md Section 1.2).
 *
 * <p>접속을 열고, 세션을 대상 스키마로 맞추고, 읽기 요청이면 읽기 전용 트랜잭션으로 둔다.
 * 접속을 풀에 두지 않는다 — 요청이 끝나면 닫는다. 자격 증명은 메서드 인자로만 지나가고 보관하지 않는다.
 */
@Slf4j
@Component
public class TargetDatabase {

    private final Map<String, Dialect> dialects;
    private final LimitsProperties limits;

    public TargetDatabase(List<Dialect> dialects, LimitsProperties limits) {
        this.dialects = dialects.stream().collect(Collectors.toUnmodifiableMap(Dialect::dbmsType, Function.identity()));
        this.limits = limits;
    }

    /** 커넥션의 DBMS에 맞는 방언 — 지원하지 않는 DBMS면 접속할 수 없는 것으로 본다 */
    public Dialect dialectOf(ConnectionAccess access) {
        Dialect dialect = dialects.get(access.dbmsType() == null ? "" : access.dbmsType().trim().toLowerCase());
        if (dialect == null) {
            throw BusinessException.of(ErrorCode.CONNECTION_UNREACHABLE, "jdbc.unsupported", access.dbmsType());
        }
        return dialect;
    }

    /**
     * 읽기 전용 접속 — 읽기 전용 트랜잭션을 연다. 분류가 틀려 쓰기 문장이 섞여도 데이터베이스가 거부한다
     * (Section 1.2 원칙 7). 호출부는 끝에 {@link Connection#rollback()} 없이 닫기만 하면 된다.
     */
    public Connection openReadOnly(ConnectionAccess access) {
        Dialect dialect = dialectOf(access);
        Connection connection = connect(dialect, access);
        try {
            dialect.prepareSession(connection, access);
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException e) {
            closeQuietly(connection);
            throw unreachable(e);
        }
    }

    /** 문장 실행 제한 시간을 건 Statement 설정 — 넘으면 드라이버가 문장을 취소한다(Section 2.3) */
    public void applyStatementTimeout(Statement statement) throws SQLException {
        statement.setQueryTimeout((int) Math.max(1, limits.statementTimeout().toSeconds()));
    }

    private Connection connect(Dialect dialect, ConnectionAccess access) {
        Properties props = new Properties();
        props.setProperty("user", access.username());
        props.setProperty("password", access.password());
        // 소켓 제한은 문장 제한보다 조금 길게 — 문장 취소가 먼저 동작하고, 그래도 안 끝나면 소켓이 끊는다
        Duration socketTimeout = limits.statementTimeout().plusSeconds(2);
        dialect.applyDriverProperties(props, limits.connectTimeout(), socketTimeout);
        try {
            return DriverManager.getConnection(dialect.jdbcUrl(access), props);
        } catch (SQLException e) {
            throw unreachable(e);
        }
    }

    /** 접속 실패 — 예외 원문(호스트·계정이 섞일 수 있다)은 내보내지 않고 분류 문구만 싣는다(Section 4) */
    private static BusinessException unreachable(SQLException e) {
        log.debug("대상 데이터베이스 접속 실패(sqlState={})", e.getSQLState());
        return BusinessException.of(ErrorCode.CONNECTION_UNREACHABLE, classify(e));
    }

    private static String classify(SQLException e) {
        String state = e.getSQLState();
        if (e instanceof SQLTimeoutException) {
            return "jdbc.timeout";
        }
        if (state != null && state.startsWith("28")) {
            return "jdbc.auth";
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (message.contains("timeout") || message.contains("timed out")) {
            return "jdbc.timeout";
        }
        if (message.contains("access denied") || message.contains("password authentication failed")) {
            return "jdbc.auth";
        }
        if ((state != null && state.startsWith("08")) || message.contains("unknown database")
                || message.contains("does not exist") || message.contains("connection refused")) {
            return "jdbc.unreachable";
        }
        return "jdbc.generic";
    }

    public static void closeQuietly(Connection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // 닫기 실패는 호출 결과에 영향을 주지 않는다
        }
    }
}
