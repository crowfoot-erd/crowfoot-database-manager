package net.java21.crowfoot.database.jdbc;

import java.sql.Types;
import java.util.Locale;
import java.util.Map;

/**
 * JDBC 타입 → 표시 이름·분류 (00-data-browser.md Section 2.2).
 */
public final class SqlTypes {

    /** PostgreSQL 내부 이름 → 익숙한 표기 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("INT2", "SMALLINT"), Map.entry("INT4", "INTEGER"), Map.entry("INT8", "BIGINT"),
            Map.entry("SERIAL", "INTEGER"), Map.entry("BIGSERIAL", "BIGINT"),
            Map.entry("FLOAT4", "REAL"), Map.entry("FLOAT8", "DOUBLE PRECISION"),
            Map.entry("BOOL", "BOOLEAN"), Map.entry("BPCHAR", "CHAR"));

    private SqlTypes() {
    }

    /** 표시용 타입 이름 — 문자·십진 타입에만 길이·정밀도를 붙인다 */
    public static String displayName(String rawTypeName, int jdbcType, int size, int scale) {
        String upper = rawTypeName == null ? "" : rawTypeName.toUpperCase(Locale.ROOT);
        String name = ALIASES.getOrDefault(upper, upper);
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.BINARY, Types.VARBINARY ->
                    size > 0 && size < Integer.MAX_VALUE && !isUnboundedText(name) ? name + "(" + size + ")" : name;
            case Types.DECIMAL, Types.NUMERIC ->
                    size > 0 && size < 1000 ? name + "(" + size + (scale > 0 ? "," + scale : "") + ")" : name;
            default -> name;
        };
    }

    private static boolean isUnboundedText(String name) {
        return name.contains("TEXT") || name.equals("JSON") || name.equals("JSONB") || name.equals("UUID");
    }

    /** 타입 분류 — 에디터의 데이터 타입 분류와 같은 값에 binary·other를 더한 것 */
    public static String category(String rawTypeName, int jdbcType) {
        String lower = rawTypeName == null ? "" : rawTypeName.toLowerCase(Locale.ROOT);
        if (lower.equals("json") || lower.equals("jsonb")) {
            return "json";
        }
        if (lower.equals("uuid")) {
            return "uuid";
        }
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "integer";
            case Types.DECIMAL, Types.NUMERIC -> "decimal";
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> "float";
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR ->
                    lower.contains("text") ? "text" : "character";
            case Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.SQLXML -> "text";
            case Types.BOOLEAN -> "boolean";
            case Types.BIT -> "boolean";
            case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "datetime";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "binary";
            default -> "other";
        };
    }

    public static boolean isBinary(int jdbcType) {
        return jdbcType == Types.BINARY || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY || jdbcType == Types.BLOB;
    }
}
