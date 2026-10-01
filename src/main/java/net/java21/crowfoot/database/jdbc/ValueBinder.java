package net.java21.crowfoot.database.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

/**
 * 문자열 값 → 바인딩 파라미터 (00-data-browser.md Section 2.2).
 *
 * <p>값은 문자열 또는 null로 오간다. 변환은 데이터베이스에 맡긴다 — 문자열로 바인딩하면 PostgreSQL은
 * 컬럼 타입으로 해석하고(stringtype=unspecified) MySQL은 암묵 변환한다. 예외는 불리언 하나다:
 * 응답에서 불리언을 {@code "true"}·{@code "false"}로 내려보내므로, 같은 표기를 그대로 되받아야 한다.
 * MySQL의 TINYINT(1)은 그 문자열을 숫자로 바꾸지 못한다.
 */
public final class ValueBinder {

    private ValueBinder() {
    }

    /**
     * @param category 대상 컬럼의 타입 분류 — 모르면 null
     */
    public static void bind(PreparedStatement statement, int index, String value, String category) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
            return;
        }
        if ("boolean".equals(category)) {
            String lower = value.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("true") || lower.equals("false")) {
                statement.setBoolean(index, lower.equals("true"));
                return;
            }
        }
        statement.setString(index, value);
    }
}
