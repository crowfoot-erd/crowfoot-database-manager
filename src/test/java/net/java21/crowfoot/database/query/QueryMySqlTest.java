package net.java21.crowfoot.database.query;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.query.dto.QueryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 콘솔 계약 — MySQL 실물. docker 데몬이 없으면 스킵한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class QueryMySqlTest extends QueryContractTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shop").withUsername("crowfoot").withPassword("test-pass-1");

    @Override
    void resetData() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS scratch_t");
            statement.execute("DROP TABLE IF EXISTS items");
            statement.execute("CREATE TABLE items (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, qty INT NOT NULL)");
            statement.execute("INSERT INTO items VALUES (1, 'apple', 10), (2, 'pear', 20), (3, 'plum', 30)");
        }
    }

    @Override
    ConnectionAccess access() {
        return new ConnectionAccess(CONNECTION, "개발 MySQL", WORKSPACE, "EDITOR", "mysql",
                MYSQL.getHost(), MYSQL.getMappedPort(3306), "shop", null, "crowfoot", "test-pass-1");
    }

    @Override
    String sleepSql() {
        return "SELECT SLEEP(20)";
    }

    @Override
    String writeDisguisedAsRead() {
        return "WITH doomed AS (SELECT id FROM items) DELETE FROM items WHERE id IN (SELECT id FROM doomed)";
    }

    @Test
    @DisplayName("SHOW·DESCRIBE도 읽기로 실행된다")
    void runsShowAndDescribe() {
        QueryResponse tables = run("SHOW TABLES");
        assertThat(tables.ok()).isTrue();
        assertThat(tables.rows()).anySatisfy(row -> assertThat(row).containsExactly("items"));

        assertThat(run("DESCRIBE items").rows()).hasSize(3);
    }

    @Test
    @DisplayName("백슬래시로 이스케이프한 따옴표 뒤의 세미콜론은 문장 경계가 아니다")
    void backslashEscapedQuoteIsOneStatement() {
        QueryResponse response = run("SELECT 'a\\'; b' AS v");

        assertThat(response.ok()).isTrue();
        assertThat(response.rows().get(0)).containsExactly("a'; b");
    }
}
