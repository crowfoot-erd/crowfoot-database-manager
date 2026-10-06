package net.java21.crowfoot.database.browse;

import net.java21.crowfoot.database.browse.dto.RowsRequest;
import net.java21.crowfoot.database.browse.dto.RowsRequest.Filter;
import net.java21.crowfoot.database.browse.dto.RowsRequest.Op;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데이터 조회 계약 — PostgreSQL 실물(지정 스키마 {@code shop}). docker 데몬이 없으면 스킵한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class BrowsePostgresTest extends BrowseContractTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("app").withUsername("crowfoot").withPassword("test-pass-1");

    @BeforeAll
    static void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA shop");
            // 같은 이름의 테이블을 다른 스키마에도 둔다 — 지정 스키마로 한정되는지 확인
            statement.execute("CREATE TABLE public.users (id INT PRIMARY KEY, leaked TEXT)");
            statement.execute("SET search_path TO shop");
            statement.execute("""
                    CREATE TABLE users (
                      id BIGSERIAL PRIMARY KEY,
                      email VARCHAR(191) NOT NULL,
                      name VARCHAR(50) NULL,
                      is_active BOOLEAN NOT NULL DEFAULT TRUE,
                      balance NUMERIC(20,2) NOT NULL,
                      big BIGINT NOT NULL,
                      bio TEXT,
                      avatar BYTEA,
                      created_at TIMESTAMP NOT NULL,
                      CONSTRAINT uk_users_email UNIQUE (email)
                    )""");
            statement.execute("COMMENT ON TABLE users IS '회원'");
            statement.execute("""
                    CREATE TABLE orders (
                      id BIGSERIAL PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      status VARCHAR(20) NOT NULL,
                      status_code VARCHAR(20) GENERATED ALWAYS AS (upper(status)) STORED,
                      ordered_at TIMESTAMPTZ NOT NULL,
                      CONSTRAINT fk_orders_user_id FOREIGN KEY (user_id) REFERENCES users (id)
                    )""");
            statement.execute("CREATE INDEX idx_orders_user_id ON orders (user_id)");
            statement.execute("CREATE TABLE order_logs (message VARCHAR(100))");
            statement.execute("CREATE TABLE \"orderXlogs\" (id INT PRIMARY KEY, other VARCHAR(10))");
            statement.execute("CREATE VIEW paid_orders AS SELECT id, user_id FROM orders WHERE status = 'PAID'");
            statement.execute("INSERT INTO users (id, email, name, is_active, balance, big, bio, avatar, created_at) VALUES"
                    + " (1, 'a@example.com', 'Kim', TRUE, 12345678901234567.89, 9007199254740993, '" + longText() + "', decode('" + blobHex() + "', 'hex'), '2026-09-30 05:10:00'),"
                    + " (2, 'b@example.com', NULL, FALSE, 0.50, 1, '', NULL, '2026-09-01 00:00:00'),"
                    + " (3, 'c_d%e@example.com', 'Lee', TRUE, 1.00, 2, 'x', NULL, '2026-09-02 00:00:00')");
            statement.execute("INSERT INTO orders (id, user_id, status, ordered_at) VALUES"
                    + " (1, 1, 'PAID', '2026-09-10 10:00:00+09'), (2, 1, 'PAID', '2026-09-20 10:00:00+09'),"
                    + " (3, 2, 'READY', '2026-09-21 10:00:00+09'), (4, 2, 'PAID', '2026-09-22 10:00:00+09'),"
                    + " (5, 1, 'CANCELLED', '2026-09-23 10:00:00+09')");
        }
    }

    @Override
    ConnectionAccess access() {
        return new ConnectionAccess(CONNECTION, "개발 PG", WORKSPACE, "EDITOR", "postgresql",
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), "app", "shop", "crowfoot", "test-pass-1");
    }

    @Override
    String decimalTypeName() {
        return "NUMERIC";
    }

    @Test
    @DisplayName("지정 스키마로 한정된다 — 다른 스키마의 같은 이름 테이블이 섞이지 않는다")
    void scopedToSchema() {
        assertThat(service.objects(USER, WORKSPACE, CONNECTION).schema()).isEqualTo("shop");
        assertThat(service.structure(USER, WORKSPACE, CONNECTION, "users").columns())
                .noneMatch(column -> column.name().equals("leaked"));
    }

    @Test
    @DisplayName("시간대가 있는 시각은 오프셋을 붙여 내려간다")
    void rendersTimestampWithZone() {
        var response = service.rows(USER, WORKSPACE, CONNECTION, "orders", new RowsRequest(1, 1, null, null));
        int index = response.columns().stream().map(c -> c.name()).toList().indexOf("ordered_at");

        assertThat((String) response.rows().get(0).get(index)).matches("2026-09-10T\\d{2}:00:00(Z|[+-]\\d{2}:\\d{2})");
    }

    @Test
    @DisplayName("값이 컬럼 타입으로 변환되지 않으면 INVALID_VALUE")
    void rejectsUnconvertibleValue() {
        assertError(() -> service.rows(USER, WORKSPACE, CONNECTION, "orders", new RowsRequest(null, null,
                List.of(new Filter("user_id", Op.EQ, "abc")), null)), ErrorCode.INVALID_VALUE);
    }

    @Test
    @DisplayName("없는 스키마를 가리키는 커넥션은 접속 실패로 분류된다")
    void unknownSchemaIsUnreachable() {
        ConnectionAccess a = access();
        ConnectionAccess wrongSchema = new ConnectionAccess(a.connectionId(), a.connectionName(), a.workspaceId(),
                a.role(), a.dbmsType(), a.host(), a.port(), a.databaseName(), "nope", a.username(), a.password());

        assertError(() -> serviceFor(LIMITS, wrongSchema).objects(USER, WORKSPACE, CONNECTION),
                ErrorCode.CONNECTION_UNREACHABLE);
    }
}
