package net.java21.crowfoot.database.browse;

import net.java21.crowfoot.database.browse.dto.RowsRequest;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.jdbc.Dialect;
import net.java21.crowfoot.database.jdbc.TableStructure;
import net.java21.crowfoot.database.jdbc.ValueBinder;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 조건 목록 → WHERE 절 (00-data-browser.md Section 3.3).
 *
 * <p>컬럼 이름은 카탈로그에서 읽은 구조에 정확히 일치할 때만 쓰고(Section 2.4), 값은 전부 바인딩 파라미터로 넣는다.
 * 값은 문자열로 바인딩하고 변환은 데이터베이스가 한다 — 변환할 수 없으면 실행 때 INVALID_VALUE가 된다.
 */
final class RowFilters {

    private static final int IN_VALUES_MAX = 50;
    /** LIKE 이스케이프 문자 — 백슬래시는 DBMS마다 문자열 리터럴 해석이 달라 쓰지 않는다 */
    private static final char LIKE_ESCAPE = '!';

    private final String sql;
    private final List<String> parameters;
    /** parameters와 같은 순서 — 값이 향하는 컬럼의 타입 분류. 문자 비교(LIKE)는 null */
    private final List<String> categories;

    private RowFilters(String sql, List<String> parameters, List<String> categories) {
        this.sql = sql;
        this.parameters = parameters;
        this.categories = categories;
    }

    /** {@code " WHERE …"} 또는 빈 문자열 */
    String sql() {
        return sql;
    }

    /** 조건 값을 순서대로 바인딩하고 다음 파라미터 번호를 돌려준다 */
    int bind(PreparedStatement statement, int startIndex) throws SQLException {
        int index = startIndex;
        for (int i = 0; i < parameters.size(); i++) {
            ValueBinder.bind(statement, index++, parameters.get(i), categories.get(i));
        }
        return index;
    }

    static RowFilters of(List<RowsRequest.Filter> filters, TableStructure structure, Dialect dialect, int filtersMax) {
        if (filters == null || filters.isEmpty()) {
            return new RowFilters("", List.of(), List.of());
        }
        if (filters.size() > filtersMax) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "filters");
        }
        List<String> clauses = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        for (RowsRequest.Filter filter : filters) {
            if (structure.column(filter.column()) == null) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.filter.unknown-column", filter.column());
            }
            String column = dialect.quote(filter.column());
            String category = structure.column(filter.column()).category();
            switch (filter.op()) {
                case IS_NULL -> clauses.add(column + " IS NULL");
                case IS_NOT_NULL -> clauses.add(column + " IS NOT NULL");
                case CONTAINS -> {
                    clauses.add(dialect.castToText(column) + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'");
                    parameters.add("%" + escapeLike(text(filter)) + "%");
                    categories.add(null);
                }
                case STARTS_WITH -> {
                    clauses.add(dialect.castToText(column) + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'");
                    parameters.add(escapeLike(text(filter)) + "%");
                    categories.add(null);
                }
                case IN -> {
                    List<String> values = texts(filter);
                    clauses.add(column + " IN (" + "?, ".repeat(values.size() - 1) + "?)");
                    parameters.addAll(values);
                    values.forEach(value -> categories.add(category));
                }
                default -> {
                    clauses.add(column + " " + filter.op().comparison() + " ?");
                    parameters.add(text(filter));
                    categories.add(category);
                }
            }
        }
        // categories에는 null이 들어가므로 List.copyOf를 쓰지 않는다
        return new RowFilters(" WHERE " + String.join(" AND ", clauses), List.copyOf(parameters),
                java.util.Collections.unmodifiableList(categories));
    }

    private static String text(RowsRequest.Filter filter) {
        if (filter.value() instanceof String value) {
            return value;
        }
        throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.filter.value-required", filter.column());
    }

    private static List<String> texts(RowsRequest.Filter filter) {
        if (filter.value() instanceof List<?> values && !values.isEmpty() && values.size() <= IN_VALUES_MAX
                && values.stream().allMatch(String.class::isInstance)) {
            return values.stream().map(String.class::cast).toList();
        }
        throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.filter.value-required", filter.column());
    }

    private static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (char ch : value.toCharArray()) {
            if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }
}
