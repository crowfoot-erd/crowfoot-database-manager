package net.java21.crowfoot.database.edit;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 행 편집 계약 — MySQL 실물. docker 데몬이 없으면 스킵한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class EditMySqlTest extends EditContractTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shop").withUsername("crowfoot").withPassword("test-pass-1");

    @Override
    Connection direct() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Override
    void resetData() throws Exception {
        try (Connection connection = direct(); Statement statement = connection.createStatement()) {
            statement.execute("DROP VIEW IF EXISTS done_notes");
            statement.execute("DROP TABLE IF EXISTS priced");
            statement.execute("DROP TABLE IF EXISTS tags");
            statement.execute("DROP TABLE IF EXISTS note_logs");
            statement.execute("DROP TABLE IF EXISTS notes");
            statement.execute("""
                    CREATE TABLE notes (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      title VARCHAR(50) NOT NULL,
                      body TEXT NULL,
                      done TINYINT(1) NOT NULL DEFAULT 0,
                      qty INT NULL,
                      attachment BLOB NULL
                    )""");
            statement.execute("CREATE TABLE tags (note_id BIGINT NOT NULL, tag VARCHAR(30) NOT NULL, weight INT NULL, PRIMARY KEY (note_id, tag))");
            statement.execute("CREATE TABLE note_logs (message VARCHAR(100))");
            // 생성 컬럼(v1.35) — 행 편집과 샘플 데이터는 이 컬럼에 값을 넣지 않는다
            statement.execute("CREATE TABLE priced (id BIGINT PRIMARY KEY, qty INT NOT NULL, price INT NOT NULL, stamped_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "total INT GENERATED ALWAYS AS (qty * price) STORED)");
            statement.execute("CREATE VIEW done_notes AS SELECT id, title FROM notes WHERE done = 1");
            statement.execute("INSERT INTO notes (id, title, body, done, qty) VALUES (1, '첫 메모', '" + longText() + "', 0, 10), (2, '둘째 메모', '본문 2', 0, NULL)");
            statement.execute("INSERT INTO tags VALUES (1, 'work', 1), (2, 'home', NULL)");
        }
    }

    @Override
    ConnectionAccess access() {
        return new ConnectionAccess(CONNECTION, "개발 MySQL", WORKSPACE, "EDITOR", "mysql",
                MYSQL.getHost(), MYSQL.getMappedPort(3306), "shop", null, "crowfoot", "test-pass-1");
    }

    @Override
    String falseLiteral() {
        return "0";
    }

    @Override
    String trueLiteral() {
        return "1";
    }
}
