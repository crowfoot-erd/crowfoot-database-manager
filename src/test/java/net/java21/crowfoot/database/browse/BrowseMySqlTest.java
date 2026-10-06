package net.java21.crowfoot.database.browse;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 데이터 조회 계약 — MySQL 실물. docker 데몬이 없으면 스킵한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class BrowseMySqlTest extends BrowseContractTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shop").withUsername("crowfoot").withPassword("test-pass-1");

    @BeforeAll
    static void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      email VARCHAR(191) NOT NULL,
                      name VARCHAR(50) NULL,
                      is_active TINYINT(1) NOT NULL DEFAULT 1,
                      balance DECIMAL(20,2) NOT NULL,
                      big BIGINT NOT NULL,
                      bio TEXT,
                      avatar BLOB,
                      created_at DATETIME NOT NULL,
                      UNIQUE KEY uk_users_email (email)
                    ) COMMENT='회원'""");
            statement.execute("""
                    CREATE TABLE orders (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      status VARCHAR(20) NOT NULL,
                      status_code VARCHAR(20) GENERATED ALWAYS AS (UPPER(status)) STORED,
                      ordered_at DATETIME NOT NULL,
                      KEY idx_orders_user_id (user_id),
                      CONSTRAINT fk_orders_user_id FOREIGN KEY (user_id) REFERENCES users (id)
                    )""");
            statement.execute("CREATE TABLE order_logs (message VARCHAR(100))");
            // 복합 기본 키 — 키 기준 페이지 넘김(Section 5.11)
            statement.execute("CREATE TABLE order_items (order_id BIGINT NOT NULL, line_no INT NOT NULL,"
                    + " qty INT NOT NULL, PRIMARY KEY (order_id, line_no))");
            statement.execute("INSERT INTO order_items (order_id, line_no, qty) VALUES"
                    + " (3, 2, 6), (1, 1, 1), (1, 3, 3), (2, 1, 4), (1, 2, 2), (3, 1, 5)");
            statement.execute("CREATE TABLE orderXlogs (id INT PRIMARY KEY, other VARCHAR(10))");
            statement.execute("CREATE VIEW paid_orders AS SELECT id, user_id FROM orders WHERE status = 'PAID'");
            statement.execute("INSERT INTO users (id, email, name, is_active, balance, big, bio, avatar, created_at) VALUES"
                    + " (1, 'a@example.com', 'Kim', 1, 12345678901234567.89, 9007199254740993, '" + longText() + "', x'" + blobHex() + "', '2026-09-30 05:10:00'),"
                    + " (2, 'b@example.com', NULL, 0, 0.50, 1, '', NULL, '2026-09-01 00:00:00'),"
                    + " (3, 'c_d%e@example.com', 'Lee', 1, 1.00, 2, 'x', NULL, '2026-09-02 00:00:00')");
            statement.execute("INSERT INTO orders (id, user_id, status, ordered_at) VALUES"
                    + " (1, 1, 'PAID', '2026-09-10 10:00:00'), (2, 1, 'PAID', '2026-09-20 10:00:00'),"
                    + " (3, 2, 'READY', '2026-09-21 10:00:00'), (4, 2, 'PAID', '2026-09-22 10:00:00'),"
                    + " (5, 1, 'CANCELLED', '2026-09-23 10:00:00')");
        }
    }

    @Override
    ConnectionAccess access() {
        return new ConnectionAccess(CONNECTION, "개발 MySQL", WORKSPACE, "EDITOR", "mysql",
                MYSQL.getHost(), MYSQL.getMappedPort(3306), "shop", null, "crowfoot", "test-pass-1");
    }

    @Override
    String decimalTypeName() {
        return "DECIMAL";
    }
}
