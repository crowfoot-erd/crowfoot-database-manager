package net.java21.crowfoot.database.jdbc;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ResultSet 셀 → 응답 값 (00-data-browser.md Section 2.2).
 *
 * <p>셀 값은 넷 중 하나다 — {@code null}, 문자열, 잘린 문자 값 객체, 이진 값 객체.
 * 숫자를 JSON 숫자로 내리지 않는다(BIGINT·DECIMAL이 브라우저에서 정밀도를 잃는다).
 */
public final class CellValues {

    private static final int BINARY_PREVIEW_BYTES = 32;
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private CellValues() {
    }

    /**
     * @param cellTextLength 문자 셀을 자르는 길이 — 0 이하면 자르지 않는다(긴 값 읽기)
     */
    public static Object read(ResultSet rs, ResultSetMetaData meta, int index, int cellTextLength) throws SQLException {
        int jdbcType = meta.getColumnType(index);
        if (SqlTypes.isBinary(jdbcType)) {
            byte[] bytes = rs.getBytes(index);
            return bytes == null ? null : binary(bytes);
        }
        String text = readText(rs, meta, index, jdbcType);
        if (text == null) {
            return null;
        }
        if (cellTextLength > 0 && text.length() > cellTextLength) {
            Map<String, Object> truncated = new LinkedHashMap<>();
            truncated.put("truncated", true);
            truncated.put("text", text.substring(0, cellTextLength));
            truncated.put("length", text.length());
            return truncated;
        }
        return text;
    }

    /** 응답 크기 추정에 쓰는 셀의 글자 수 */
    public static int sizeOf(Object cell) {
        if (cell == null) {
            return 4;
        }
        if (cell instanceof String text) {
            return text.length() + 2;
        }
        if (cell instanceof Map<?, ?> map && map.get("text") instanceof String text) {
            return text.length() + 48;
        }
        return 120;
    }

    private static Map<String, Object> binary(byte[] bytes) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("binary", true);
        value.put("length", bytes.length);
        value.put("previewHex", HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, BINARY_PREVIEW_BYTES)));
        return value;
    }

    private static String readText(ResultSet rs, ResultSetMetaData meta, int index, int jdbcType) throws SQLException {
        try {
            switch (jdbcType) {
                case Types.BOOLEAN -> {
                    boolean value = rs.getBoolean(index);
                    return rs.wasNull() ? null : Boolean.toString(value);
                }
                case Types.BIT -> {
                    // 한 비트(MySQL TINYINT(1)·BIT(1), PostgreSQL bool)만 불리언으로 본다 — 여러 비트는 문자 표현 그대로
                    if (meta.getPrecision(index) <= 1) {
                        boolean value = rs.getBoolean(index);
                        return rs.wasNull() ? null : Boolean.toString(value);
                    }
                    return rs.getString(index);
                }
                case Types.DATE -> {
                    LocalDate value = rs.getObject(index, LocalDate.class);
                    return value == null ? null : value.toString();
                }
                case Types.TIME -> {
                    LocalTime value = rs.getObject(index, LocalTime.class);
                    return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_TIME);
                }
                case Types.TIMESTAMP -> {
                    // pgjdbc는 timestamptz도 TIMESTAMP로 보고한다 — 타입 이름으로 시간대 유무를 가른다
                    String typeName = meta.getColumnTypeName(index);
                    if (typeName != null && typeName.toLowerCase(Locale.ROOT).contains("tz")) {
                        OffsetDateTime value = rs.getObject(index, OffsetDateTime.class);
                        return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    }
                    LocalDateTime value = rs.getObject(index, LocalDateTime.class);
                    return value == null ? null : value.format(LOCAL_DATE_TIME);
                }
                case Types.TIMESTAMP_WITH_TIMEZONE -> {
                    OffsetDateTime value = rs.getObject(index, OffsetDateTime.class);
                    return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                }
                default -> {
                    return rs.getString(index);
                }
            }
        } catch (SQLException conversion) {
            // 드라이버가 그 자바 타입으로 못 바꾸는 값(무한대 날짜 등) — 문자 표현으로 물러선다
            return rs.getString(index);
        }
    }
}
