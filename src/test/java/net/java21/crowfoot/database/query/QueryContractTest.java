package net.java21.crowfoot.database.query;

import net.java21.crowfoot.database.client.CoreClient;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import net.java21.crowfoot.database.jdbc.ColumnMeta;
import net.java21.crowfoot.database.jdbc.MySqlDialect;
import net.java21.crowfoot.database.jdbc.PostgresDialect;
import net.java21.crowfoot.database.jdbc.TargetDatabase;
import net.java21.crowfoot.database.query.dto.QueryRequest;
import net.java21.crowfoot.database.query.dto.QueryResponse;
import net.java21.crowfoot.database.support.UserConcurrencyLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 콘솔 계약 — MySQL·PostgreSQL 실물에서 같은 시나리오를 돌린다(00-data-browser.md Section 3.6).
 * 하위 클래스가 컨테이너와 방언별 문장을 댄다. 테이블 items(id, name, qty) 3행에서 시작한다.
 */
abstract class QueryContractTest {

    static final long USER = 7L;
    static final String WORKSPACE = "34";
    static final String CONNECTION = "302";

    final List<String[]> audits = new ArrayList<>();
    QueryService service;

    abstract ConnectionAccess access();

    /** 테이블을 처음 상태(3행)로 되돌린다 */
    abstract void resetData() throws Exception;

    /** 제한 시간을 넘기는 문장 */
    abstract String sleepSql();

    /** 첫 키워드는 읽기(WITH)지만 실제로는 지우는 문장 */
    abstract String writeDisguisedAsRead();

    static LimitsProperties limits(Duration statementTimeout, int sqlLengthMax) {
        return new LimitsProperties(Duration.ofSeconds(5), statementTimeout, 100, 500, 500, 1000, 2000, 1_000_000,
                5 * 1024 * 1024, 10, 3, 100, sqlLengthMax, 2);
    }

    QueryService serviceFor(LimitsProperties limits) {
        CoreClient core = new CoreClient() {
            @Override
            public ConnectionAccess requireAccess(long userId, String workspaceId, String connectionId) {
                return access();
            }

            @Override
            public void recordAuditLog(long actorId, String action, String detail) {
                audits.add(new String[] {Long.toString(actorId), action, detail});
            }
        };
        return new QueryService(core, new TargetDatabase(List.of(new MySqlDialect(), new PostgresDialect()), limits),
                new UserConcurrencyLimiter(limits), limits, JsonMapper.builder().build());
    }

    @BeforeEach
    void wire() throws Exception {
        resetData();
        audits.clear();
        service = serviceFor(limits(Duration.ofSeconds(8), 100_000));
    }

    QueryResponse run(String sql) {
        return service.execute(USER, WORKSPACE, CONNECTION, new QueryRequest(sql, null, null));
    }

    QueryResponse runConfirmed(String sql) {
        return service.execute(USER, WORKSPACE, CONNECTION, new QueryRequest(sql, null, true));
    }

    String count() {
        return (String) run("SELECT COUNT(*) FROM items").rows().get(0).get(0);
    }

    static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("읽기 문장 — 확인 없이 실행하고 컬럼·행을 돌려준다. 감사 기록에는 SQL 본문이 없다")
    void runsReadStatement() {
        QueryResponse response = run("SELECT id, name, qty FROM items ORDER BY id;");

        assertThat(response.kind()).isEqualTo("READ");
        assertThat(response.ok()).isTrue();
        assertThat(response.columns()).extracting(ColumnMeta::name).containsExactly("id", "name", "qty");
        assertThat(response.rows()).hasSize(3);
        assertThat(response.rows().get(0)).containsExactly("1", "apple", "10");
        assertThat(response.rowCount()).isEqualTo(3);
        assertThat(response.truncated()).isFalse();
        assertThat(response.error()).isNull();

        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[1]).isEqualTo("CONNECTION_QUERY_EXECUTED");
        assertThat(audits.get(0)[2])
                .contains("\"kind\":\"READ\"").contains("\"sqlLength\":44").contains("\"rows\":3").contains("\"ok\":true")
                .containsPattern("\"sqlHash\":\"[0-9a-f]{16}\"")
                .doesNotContain("items").doesNotContain("apple").doesNotContain(access().password());
    }

    @Test
    @DisplayName("결과 행 상한 — 넘으면 자르고 truncated로 알린다")
    void truncatesAtMaxRows() {
        QueryResponse response = service.execute(USER, WORKSPACE, CONNECTION,
                new QueryRequest("SELECT id FROM items ORDER BY id", 2, null));

        assertThat(response.rows()).hasSize(2);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    @DisplayName("쓰기 문장 — 확인 없이는 실행하지 않고 종류를 알려 준다. 확인하면 실행하고 바꾼 행 수를 돌려준다")
    void writeRequiresConfirmation() {
        assertThatThrownBy(() -> run("UPDATE items SET qty = qty + 1"))
                .isInstanceOfSatisfying(ConfirmationRequiredException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFIRMATION_REQUIRED);
                    assertThat(ex.getKind()).isEqualTo(StatementKind.WRITE);
                });
        assertThat(audits).isEmpty();
        assertThat(run("SELECT qty FROM items WHERE id = 1").rows().get(0)).containsExactly("10");

        QueryResponse response = runConfirmed("UPDATE items SET qty = qty + 1");

        assertThat(response.kind()).isEqualTo("WRITE");
        assertThat(response.ok()).isTrue();
        assertThat(response.affectedRows()).isEqualTo(3L);
        assertThat(response.rows()).isNull();
        assertThat(run("SELECT qty FROM items WHERE id = 1").rows().get(0)).containsExactly("11");
    }

    @Test
    @DisplayName("구조 문장 — 확인 뒤 실행한다")
    void ddlRequiresConfirmation() {
        assertThatThrownBy(() -> run("CREATE TABLE scratch_t (id INT)"))
                .isInstanceOfSatisfying(ConfirmationRequiredException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(StatementKind.DDL));

        QueryResponse created = runConfirmed("CREATE TABLE scratch_t (id INT)");
        assertThat(created.kind()).isEqualTo("DDL");
        assertThat(created.ok()).isTrue();
        assertThat(created.affectedRows()).isNull();
        assertThat(run("SELECT COUNT(*) FROM scratch_t").ok()).isTrue();

        assertThat(runConfirmed("DROP TABLE scratch_t").ok()).isTrue();
        assertThat(run("SELECT COUNT(*) FROM scratch_t").ok()).isFalse();
    }

    @Test
    @DisplayName("데이터베이스가 거부한 문장 — 예외가 아니라 ok:false와 데이터베이스 문구로 답한다")
    void reportsDatabaseRejection() {
        QueryResponse response = run("SELECT * FROM no_such_table");

        assertThat(response.ok()).isFalse();
        assertThat(response.kind()).isEqualTo("READ");
        assertThat(response.error().message()).containsIgnoringCase("no_such_table");
        assertThat(response.error().sqlState()).isNotBlank();
        assertThat(audits.get(0)[2]).contains("\"ok\":false");
    }

    @Test
    @DisplayName("읽기로 분류됐지만 실제로는 쓰는 문장 — 읽기 전용 트랜잭션이라 데이터베이스가 거부한다")
    void readOnlyTransactionStopsDisguisedWrites() {
        QueryResponse response = run(writeDisguisedAsRead());

        assertThat(response.kind()).isEqualTo("READ");
        assertThat(response.ok()).isFalse();
        assertThat(count()).isEqualTo("3");
    }

    @Test
    @DisplayName("문장이 둘 이상이면 실행하지 않는다 — 뒤 문장도 실행되지 않는다")
    void rejectsMultipleStatements() {
        assertError(() -> run("SELECT 1; SELECT 2"), ErrorCode.MULTIPLE_STATEMENTS);
        assertError(() -> runConfirmed("SELECT 1; DELETE FROM items"), ErrorCode.MULTIPLE_STATEMENTS);
        assertThat(count()).isEqualTo("3");
    }

    @Test
    @DisplayName("트랜잭션 제어·세션 설정·프로시저 호출은 받지 않는다")
    void rejectsUnsupportedStatements() {
        for (String sql : List.of("BEGIN", "COMMIT", "ROLLBACK", "SET autocommit = 0", "CALL do_something()", "LOCK TABLE items")) {
            assertError(() -> runConfirmed(sql), ErrorCode.UNSUPPORTED_STATEMENT);
        }
        assertThat(audits).isEmpty();
    }

    @Test
    @DisplayName("요청 검증 — 빈 문장·주석뿐인 문장·길이 초과·행 상한 초과")
    void validatesRequest() {
        assertError(() -> run("   -- 주석뿐\n"), ErrorCode.INVALID_REQUEST);
        assertError(() -> run(";"), ErrorCode.INVALID_REQUEST);
        assertError(() -> service.execute(USER, WORKSPACE, CONNECTION, new QueryRequest("SELECT 1", 1001, null)),
                ErrorCode.INVALID_REQUEST);
        QueryService shortSql = serviceFor(limits(Duration.ofSeconds(8), 10));
        assertError(() -> shortSql.execute(USER, WORKSPACE, CONNECTION, new QueryRequest("SELECT 1234567", null, null)),
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("실행 제한 시간 — 넘으면 문장을 취소하고 QUERY_TIMEOUT")
    void cancelsOnTimeout() {
        QueryService quick = serviceFor(limits(Duration.ofSeconds(1), 100_000));
        long start = System.nanoTime();

        assertError(() -> quick.execute(USER, WORKSPACE, CONNECTION, new QueryRequest(sleepSql(), null, null)),
                ErrorCode.QUERY_TIMEOUT);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    @DisplayName("EXPLAIN도 읽기로 실행된다")
    void runsExplain() {
        QueryResponse response = run("EXPLAIN SELECT * FROM items WHERE id = 1");

        assertThat(response.ok()).isTrue();
        assertThat(response.rows()).isNotEmpty();
    }
}
