package net.java21.crowfoot.database.jdbc;

import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 카탈로그 읽기 — 객체·컬럼의 실제 이름을 데이터베이스에서 읽는다(00-data-browser.md Section 2.4).
 *
 * <p>요청의 객체 이름은 여기서 읽은 목록에 정확히 일치할 때만 쓴다. SQL에는 이 클래스가 돌려준 이름만 들어간다.
 */
@Component
public class CatalogReader {

    /** 객체 한 건의 구조 — 없으면 OBJECT_NOT_FOUND */
    public TableStructure structure(Connection connection, Dialect dialect, String schema, String objectName)
            throws SQLException {
        Dialect.CatalogObject object = dialect.listObjects(connection, schema).stream()
                .filter(candidate -> candidate.name().equals(objectName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.OBJECT_NOT_FOUND));

        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = dialect.metadataCatalog(schema);
        String schemaPattern = dialect.metadataSchema(schema);
        String escapedName = escapePattern(metaData, object.name());

        List<String> primaryKey = readPrimaryKey(metaData, catalog, schemaPattern, object.name());
        List<TableStructure.Column> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(catalog, escapePattern(metaData, schemaPattern), escapedName, null)) {
            while (rs.next()) {
                // 패턴 일치라 이름이 정확히 같은 행만 받는다(이스케이프를 지원하지 않는 드라이버 방어)
                if (!object.name().equals(rs.getString("TABLE_NAME"))) {
                    continue;
                }
                String name = rs.getString("COLUMN_NAME");
                int jdbcType = rs.getInt("DATA_TYPE");
                String rawType = rs.getString("TYPE_NAME");
                String remarks = rs.getString("REMARKS");
                columns.add(new TableStructure.Column(
                        name,
                        SqlTypes.displayName(rawType, jdbcType, rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS")),
                        SqlTypes.category(rawType, jdbcType),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        primaryKey.contains(name),
                        rs.getString("COLUMN_DEF"),
                        "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")),
                        remarks == null || remarks.isBlank() ? null : remarks,
                        jdbcType));
            }
        }
        return new TableStructure(object.name(), object.view(), object.comment(), List.copyOf(columns), primaryKey,
                object.view() ? List.of() : readIndexes(metaData, catalog, schemaPattern, object.name()),
                object.view() ? List.of() : readForeignKeys(metaData, catalog, schemaPattern, object.name()));
    }

    private static List<String> readPrimaryKey(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        Map<Integer, String> bySequence = new TreeMap<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                bySequence.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return List.copyOf(bySequence.values());
    }

    private static List<TableStructure.Index> readIndexes(DatabaseMetaData metaData, String catalog, String schema,
                                                          String table) throws SQLException {
        Map<String, Boolean> unique = new LinkedHashMap<>();
        Map<String, Map<Integer, String>> columns = new LinkedHashMap<>();
        // approximate=true — 통계를 새로 계산하지 않는다(큰 테이블에서 느려지지 않게)
        try (ResultSet rs = metaData.getIndexInfo(catalog, schema, table, false, true)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (name == null || column == null) {
                    continue; // 통계 행(tableIndexStatistic)과 식 인덱스
                }
                unique.put(name, !rs.getBoolean("NON_UNIQUE"));
                columns.computeIfAbsent(name, key -> new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"), column);
            }
        }
        return columns.entrySet().stream()
                .map(entry -> new TableStructure.Index(entry.getKey(), unique.get(entry.getKey()),
                        List.copyOf(entry.getValue().values())))
                .sorted(Comparator.comparing(TableStructure.Index::name))
                .toList();
    }

    private static List<TableStructure.ForeignKey> readForeignKeys(DatabaseMetaData metaData, String catalog,
                                                                   String schema, String table) throws SQLException {
        Map<String, String> referenced = new LinkedHashMap<>();
        Map<String, Map<Integer, String[]>> pairs = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                String name = rs.getString("FK_NAME");
                if (name == null) {
                    name = rs.getString("PKTABLE_NAME") + "_fk";
                }
                referenced.put(name, rs.getString("PKTABLE_NAME"));
                pairs.computeIfAbsent(name, key -> new TreeMap<>()).put(rs.getInt("KEY_SEQ"),
                        new String[] {rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")});
            }
        }
        return pairs.entrySet().stream()
                .map(entry -> new TableStructure.ForeignKey(entry.getKey(),
                        entry.getValue().values().stream().map(pair -> pair[0]).toList(),
                        referenced.get(entry.getKey()),
                        entry.getValue().values().stream().map(pair -> pair[1]).toList()))
                .sorted(Comparator.comparing(TableStructure.ForeignKey::name))
                .toList();
    }

    /** 메타데이터 패턴 인자에서 와일드카드({@code _}·{@code %})를 이스케이프한다 — {@code order_items}가 다른 이름에 걸리지 않게 */
    private static String escapePattern(DatabaseMetaData metaData, String value) throws SQLException {
        if (value == null) {
            return null;
        }
        String escape = metaData.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
