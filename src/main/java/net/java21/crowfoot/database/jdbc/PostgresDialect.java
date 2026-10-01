package net.java21.crowfoot.database.jdbc;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * PostgreSQL — 대상은 커넥션의 지정 스키마다. 지정이 없으면 현재 스키마(search_path의 첫 스키마)다
 * (00-data-browser.md Section 1.3 — 스키마 고정 방식은 08-core/06-connection.md Section 3.2와 같다).
 */
@Component
public class PostgresDialect implements Dialect {

    @Override
    public String dbmsType() {
        return "postgresql";
    }

    @Override
    public String jdbcUrl(ConnectionAccess access) {
        return "jdbc:postgresql://" + access.host() + ":" + access.port() + "/" + access.databaseName();
    }

    @Override
    public void applyDriverProperties(Properties props, Duration connectTimeout, Duration socketTimeout) {
        props.setProperty("connectTimeout", Long.toString(Math.max(1, connectTimeout.toSeconds())));
        props.setProperty("socketTimeout", Long.toString(Math.max(1, socketTimeout.toSeconds())));
        // 문자열로 바인딩한 값을 서버가 컬럼 타입으로 해석하게 한다 — 값은 전부 문자열로 오간다(Section 2.2)
        props.setProperty("stringtype", "unspecified");
        props.setProperty("ApplicationName", "crowfoot-database-manager");
    }

    @Override
    public void prepareSession(Connection connection, ConnectionAccess access) throws SQLException {
        String schema = access.schemaName();
        if (schema == null || schema.isBlank()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
            ps.setString(1, schema.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // 08 계열 — 호출부가 접속 실패(CONNECTION_UNREACHABLE)로 분류한다
                    throw new SQLException("schema not found: " + schema, "08004");
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('search_path', ?, false)")) {
            ps.setString(1, schema.trim());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    @Override
    public String resolveSchema(Connection connection, ConnectionAccess access) throws SQLException {
        if (access.schemaName() != null && !access.schemaName().isBlank()) {
            return access.schemaName().trim();
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_schema()")) {
            rs.next();
            String schema = rs.getString(1);
            return schema == null ? "public" : schema;
        }
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String castToText(String expression) {
        return "CAST(" + expression + " AS TEXT)";
    }

    @Override
    public List<CatalogObject> listObjects(Connection connection, String schema) throws SQLException {
        // r=테이블, p=파티션 테이블, v=뷰, m=머티리얼라이즈드 뷰. reltuples는 통계가 없으면 -1이다
        String sql = """
                SELECT c.relname, c.relkind, c.reltuples, obj_description(c.oid, 'pg_class'),
                       EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = c.oid AND k.contype = 'p')
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'v', 'm')
                ORDER BY c.relname
                """;
        List<CatalogObject> objects = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(2);
                    boolean view = "v".equals(kind) || "m".equals(kind);
                    double tuples = rs.getDouble(3);
                    Long estimated = view || tuples < 0 ? null : (long) tuples;
                    String comment = rs.getString(4);
                    objects.add(new CatalogObject(rs.getString(1), view, estimated,
                            comment == null || comment.isBlank() ? null : comment,
                            !view && rs.getBoolean(5)));
                }
            }
        }
        return objects;
    }

    @Override
    public String metadataCatalog(String schema) {
        return null;
    }

    @Override
    public String metadataSchema(String schema) {
        return schema;
    }
}
