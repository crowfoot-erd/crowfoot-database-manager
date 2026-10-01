package net.java21.crowfoot.database.query;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.query.dto.QueryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 콘솔 계약 — PostgreSQL 실물(지정 스키마 {@code shop}). docker 데몬이 없으면 스킵한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class QueryPostgresTest extends QueryContractTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("app").withUsername("crowfoot").withPassword("test-pass-1");

    @Override
    void resetData() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS shop");
            statement.execute("SET search_path TO shop");
            statement.execute("DROP TABLE IF EXISTS scratch_t");
            statement.execute("DROP TABLE IF EXISTS items");
            statement.execute("CREATE TABLE items (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, qty INT NOT NULL)");
            statement.execute("INSERT INTO items VALUES (1, 'apple', 10), (2, 'pear', 20), (3, 'plum', 30)");
        }
    }

    @Override
    ConnectionAccess access() {
        return new ConnectionAccess(CONNECTION, "개발 PG", WORKSPACE, "EDITOR", "postgresql",
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), "app", "shop", "crowfoot", "test-pass-1");
    }

    @Override
    String sleepSql() {
        return "SELECT pg_sleep(20)";
    }

    @Override
    String writeDisguisedAsRead() {
        return "WITH doomed AS (DELETE FROM items RETURNING id) SELECT * FROM doomed";
    }

    @Test
    @DisplayName("따옴표 뒤에 숨긴 두 번째 문장을 실행하지 않는다 — 백슬래시는 PostgreSQL에서 이스케이프가 아니다")
    void doesNotRunHiddenSecondStatement() {
        assertError(() -> runConfirmed("SELECT '\\'; DELETE FROM items; --'"), ErrorCode.MULTIPLE_STATEMENTS);
        assertThat(count()).isEqualTo("3");
    }

    @Test
    @DisplayName("달러 인용 안의 세미콜론은 문장 경계가 아니다")
    void dollarQuotedTextIsOneStatement() {
        QueryResponse response = run("SELECT $$a; b$$ AS v");

        assertThat(response.ok()).isTrue();
        assertThat(response.rows().get(0)).containsExactly("a; b");
    }

    @Test
    @DisplayName("부작용이 있는 함수 호출도 읽기 전용 트랜잭션에서 거부된다")
    void sideEffectFunctionIsRejected() {
        runConfirmed("CREATE SEQUENCE scratch_seq");
        try {
            assertThat(run("SELECT nextval('scratch_seq')").ok()).isFalse();
        } finally {
            runConfirmed("DROP SEQUENCE scratch_seq");
        }
    }
}
