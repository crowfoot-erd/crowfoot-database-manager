package net.java21.crowfoot.database.browse;

import net.java21.crowfoot.database.browse.dto.RowsRequest;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.jdbc.CellValues;
import net.java21.crowfoot.database.jdbc.Dialect;
import net.java21.crowfoot.database.jdbc.TableStructure;
import net.java21.crowfoot.database.jdbc.ValueBinder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 키 기준 페이지 넘김 (00-data-browser.md Section 5.11) — OFFSET 대신 기본 키 값 다음(앞)부터 읽는다.
 *
 * <p>쓸 수 있는 정렬은 기본 키 순서뿐이다 — 정렬을 지정하지 않았거나(기본 키 오름차순, 복합 키 포함),
 * 단일 컬럼 기본 키 하나로만 정렬했을 때(오름·내림). 키 값은 바인딩 파라미터로만 넣는다.
 *
 * <p>복합 키 조건은 {@code a >= ? AND (a > ? OR (a = ? AND b > ?))} 꼴로 펼친다 — 행 값 비교
 * {@code (a, b) > (?, ?)}는 MySQL이 인덱스 범위로 쓰지 못하는 버전이 있어, 앞 컬럼의 범위 조건을 덧붙인다.
 */
final class Keyset {

    /** 문자열로 오가도 같은 값으로 되돌아오는 타입 분류 — 이진·부동소수·기타 타입 기본 키는 OFFSET으로 읽는다 */
    private static final Set<String> KEY_CATEGORIES =
            Set.of("integer", "decimal", "character", "text", "datetime", "uuid", "boolean");

    private static final Keyset UNUSABLE = new Keyset(false, false, null, List.of(), List.of(), List.of(), null);

    private final boolean usable;
    private final boolean backward;
    private final String condition;
    private final List<String> keyColumns;
    private final List<String> parameters;
    private final List<String> categories;
    private final String reversedOrderBy;

    private Keyset(boolean usable, boolean backward, String condition, List<String> keyColumns,
                   List<String> parameters, List<String> categories, String reversedOrderBy) {
        this.usable = usable;
        this.backward = backward;
        this.condition = condition;
        this.keyColumns = keyColumns;
        this.parameters = parameters;
        this.categories = categories;
        this.reversedOrderBy = reversedOrderBy;
    }

    /** 이 정렬에서 키 기준 페이지 넘김을 쓸 수 있는지 — 응답의 keyset */
    boolean usable() {
        return usable;
    }

    /** before 요청 — 정렬을 뒤집어 읽고 화면 순서로 되돌린다 */
    boolean backward() {
        return backward;
    }

    /** WHERE에 더할 키 조건 — after·before가 없으면 null */
    String condition() {
        return condition;
    }

    /** before 요청에 쓰는 뒤집힌 ORDER BY */
    String reversedOrderBy() {
        return reversedOrderBy;
    }

    int bind(PreparedStatement statement, int startIndex) throws SQLException {
        int index = startIndex;
        for (int i = 0; i < parameters.size(); i++) {
            ValueBinder.bind(statement, index++, parameters.get(i), categories.get(i));
        }
        return index;
    }

    /** 현재 행의 기본 키 값 — 셀 표기와 같은 문자열(자르지 않는다) */
    Map<String, String> read(ResultSet rs, ResultSetMetaData meta, TableStructure structure) throws SQLException {
        Map<String, String> key = new LinkedHashMap<>();
        for (String column : keyColumns) {
            int index = structure.columns().indexOf(structure.column(column)) + 1;
            Object value = CellValues.read(rs, meta, index, 0);
            key.put(column, value == null ? null : value.toString());
        }
        return key;
    }

    static Keyset of(List<RowsRequest.Sort> sort, TableStructure structure, Dialect dialect,
                     Map<String, String> after, Map<String, String> before) {
        boolean requested = after != null || before != null;
        if (after != null && before != null) {
            throw invalid("after, before");
        }
        Boolean descending = keyOrder(sort, structure);
        if (descending == null) {
            if (requested) {
                throw invalid(after != null ? "after" : "before");
            }
            return UNUSABLE;
        }
        List<String> keyColumns = structure.primaryKey();
        String reversed = " ORDER BY " + keyColumns.stream()
                .map(c -> dialect.quote(c) + (descending ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
        if (!requested) {
            return new Keyset(true, false, null, keyColumns, List.of(), List.of(), reversed);
        }
        Map<String, String> key = after != null ? after : before;
        String name = after != null ? "after" : "before";
        if (!key.keySet().equals(Set.copyOf(keyColumns)) || key.values().stream().anyMatch(v -> v == null)) {
            throw invalid(name);
        }
        // 다음 페이지(after)는 정렬 방향 쪽으로, 이전 페이지(before)는 반대쪽으로
        boolean greater = (after != null) != descending;
        String op = greater ? ">" : "<";
        List<String> parameters = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        String first = keyColumns.get(0);
        String condition;
        if (keyColumns.size() == 1) {
            condition = dialect.quote(first) + " " + op + " ?";
            add(parameters, categories, structure, first, key);
        } else {
            StringBuilder sql = new StringBuilder(dialect.quote(first) + " " + op + "= ?");
            add(parameters, categories, structure, first, key);
            sql.append(" AND ").append(expanded(keyColumns, 0, op, dialect, structure, key, parameters, categories));
            condition = sql.toString();
        }
        return new Keyset(true, before != null, condition, keyColumns, List.copyOf(parameters),
                java.util.Collections.unmodifiableList(categories), reversed);
    }

    /** {@code (c0 > ? OR (c0 = ? AND (c1 > ? OR (c1 = ? AND c2 > ?))))} */
    private static String expanded(List<String> columns, int i, String op, Dialect dialect, TableStructure structure,
                                   Map<String, String> key, List<String> parameters, List<String> categories) {
        String column = columns.get(i);
        String quoted = dialect.quote(column);
        add(parameters, categories, structure, column, key);
        if (i == columns.size() - 1) {
            return quoted + " " + op + " ?";
        }
        add(parameters, categories, structure, column, key);
        return "(" + quoted + " " + op + " ? OR (" + quoted + " = ? AND "
                + expanded(columns, i + 1, op, dialect, structure, key, parameters, categories) + "))";
    }

    private static void add(List<String> parameters, List<String> categories, TableStructure structure,
                            String column, Map<String, String> key) {
        parameters.add(key.get(column));
        categories.add(structure.column(column).category());
    }

    /**
     * 정렬이 기본 키 순서인지 — 내림차순이면 true, 오름차순이면 false, 기본 키 순서가 아니면 null.
     * 기본 키 타입이 문자열 왕복에 맞지 않아도 null이다.
     */
    private static Boolean keyOrder(List<RowsRequest.Sort> sort, TableStructure structure) {
        List<String> primaryKey = structure.primaryKey();
        if (primaryKey.isEmpty() || primaryKey.stream().anyMatch(c -> structure.column(c) == null
                || !KEY_CATEGORIES.contains(structure.column(c).category()))) {
            return null;
        }
        if (sort == null || sort.isEmpty()) {
            return false;
        }
        if (sort.size() == 1 && primaryKey.size() == 1 && primaryKey.get(0).equals(sort.get(0).column())) {
            return sort.get(0).direction() == RowsRequest.Direction.DESC;
        }
        return null;
    }

    private static BusinessException invalid(String field) {
        return BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.keyset.invalid", field);
    }
}
