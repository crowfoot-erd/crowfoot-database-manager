package net.java21.crowfoot.database.jdbc;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * MySQL — 대상은 커넥션의 database 하나다(00-data-browser.md Section 1.3).
 */
@Component
public class MySqlDialect implements Dialect {

    @Override
    public String dbmsType() {
        return "mysql";
    }

    @Override
    public String jdbcUrl(ConnectionAccess access) {
        return "jdbc:mysql://" + access.host() + ":" + access.port() + "/" + access.databaseName();
    }

    @Override
    public void applyDriverProperties(Properties props, Duration connectTimeout, Duration socketTimeout) {
        props.setProperty("connectTimeout", Long.toString(connectTimeout.toMillis()));
        props.setProperty("socketTimeout", Long.toString(socketTimeout.toMillis()));
        props.setProperty("characterEncoding", "UTF-8");
        // '0000-00-00' 같은 0 날짜는 예외 대신 NULL로 읽는다 — 옛 스키마의 행이 조회를 깨지 않게
        props.setProperty("zeroDateTimeBehavior", "CONVERT_TO_NULL");
        // 한 번에 한 문장만 — 드라이버 수준에서도 여러 문장 실행을 막는다(Section 3.6)
        props.setProperty("allowMultiQueries", "false");
    }

    @Override
    public void prepareSession(Connection connection, ConnectionAccess access) {
        // database는 URL로 정해진다 — 추가 설정 없음
    }

    @Override
    public String resolveSchema(Connection connection, ConnectionAccess access) {
        return access.databaseName();
    }

    @Override
    public String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String castToText(String expression) {
        return "CAST(" + expression + " AS CHAR)";
    }

    @Override
    public String nullSafeEquals(String column) {
        return column + " <=> ?";
    }

    @Override
    public String insertDefaults(String qualifiedTable) {
        return "INSERT INTO " + qualifiedTable + " () VALUES ()";
    }

    @Override
    public List<CatalogObject> listObjects(Connection connection, String schema) throws SQLException {
        String sql = """
                SELECT t.TABLE_NAME, t.TABLE_TYPE, t.TABLE_ROWS, t.TABLE_COMMENT,
                       EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS k
                               WHERE k.TABLE_SCHEMA = t.TABLE_SCHEMA AND k.TABLE_NAME = t.TABLE_NAME
                                 AND k.CONSTRAINT_TYPE = 'PRIMARY KEY')
                FROM information_schema.TABLES t
                WHERE t.TABLE_SCHEMA = ? AND t.TABLE_TYPE IN ('BASE TABLE', 'VIEW')
                ORDER BY t.TABLE_NAME
                """;
        List<CatalogObject> objects = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean view = "VIEW".equalsIgnoreCase(rs.getString(2));
                    long rows = rs.getLong(3);
                    Long estimated = view || rs.wasNull() ? null : rows;
                    String comment = rs.getString(4);
                    // 뷰의 TABLE_COMMENT는 고정 문자열 'VIEW'다 — 코멘트가 아니다
                    objects.add(new CatalogObject(rs.getString(1), view, estimated,
                            view || comment == null || comment.isBlank() ? null : comment,
                            !view && rs.getBoolean(5)));
                }
            }
        }
        return objects;
    }

    @Override
    public String metadataCatalog(String schema) {
        return schema;
    }

    @Override
    public String metadataSchema(String schema) {
        return null;
    }
}
