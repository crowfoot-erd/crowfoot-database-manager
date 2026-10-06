package net.java21.crowfoot.database.browse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.database.browse.dto.CountRequest;
import net.java21.crowfoot.database.browse.dto.CountResponse;
import net.java21.crowfoot.database.browse.dto.ObjectsResponse;
import net.java21.crowfoot.database.browse.dto.RowsRequest;
import net.java21.crowfoot.database.browse.dto.RowsResponse;
import net.java21.crowfoot.database.browse.dto.StructureResponse;
import net.java21.crowfoot.database.client.CoreClient;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import net.java21.crowfoot.database.jdbc.CatalogReader;
import net.java21.crowfoot.database.jdbc.CellValues;
import net.java21.crowfoot.database.jdbc.ColumnMeta;
import net.java21.crowfoot.database.jdbc.Dialect;
import net.java21.crowfoot.database.jdbc.SqlErrors;
import net.java21.crowfoot.database.jdbc.TableStructure;
import net.java21.crowfoot.database.jdbc.TargetDatabase;
import net.java21.crowfoot.database.support.UserConcurrencyLimiter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 데이터 조회 — 객체 목록·구조·행 조회·행 수 (00-data-browser.md Section 3.1~3.4).
 *
 * <p>모든 메서드의 흐름은 같다 — ① core 접근 확인(권한 판정 + 접속 정보) ② 사용자당 동시 실행 한도
 * ③ 읽기 전용 접속 ④ 카탈로그에서 실제 이름 확인 ⑤ 실행 ⑥ 접속 닫기. 결과를 저장하거나 캐시하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowseService {

    static final String ACTION_DATA_ACCESSED = "CONNECTION_DATA_ACCESSED";

    private final CoreClient coreClient;
    private final TargetDatabase targetDatabase;
    private final CatalogReader catalogReader;
    private final UserConcurrencyLimiter limiter;
    private final LimitsProperties limits;
    private final ObjectMapper objectMapper;

    /** 객체 목록(3.1) — 데이터 브라우저를 여는 시점이다. 접근 사실을 감사 기록으로 남긴다 */
    public ObjectsResponse objects(long userId, String workspaceId, String connectionId) {
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId);
        ObjectsResponse response = limiter.run(userId, () -> withReadOnly(access, (connection, dialect, schema) -> {
            List<ObjectsResponse.Item> items = dialect.listObjects(connection, schema).stream()
                    .map(object -> new ObjectsResponse.Item(object.name(), object.view() ? "VIEW" : "TABLE",
                            object.estimatedRows(), object.hasPrimaryKey(), object.comment()))
                    .toList();
            return new ObjectsResponse(dialect.dbmsType(), schema, items);
        }));
        coreClient.recordAuditLog(userId, ACTION_DATA_ACCESSED, accessDetail(access));
        return response;
    }

    /** 구조 보기(3.2) */
    public StructureResponse structure(long userId, String workspaceId, String connectionId, String objectName) {
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId);
        return limiter.run(userId, () -> withReadOnly(access, (connection, dialect, schema) ->
                StructureResponse.of(catalogReader.structure(connection, dialect, schema, objectName))));
    }

    /** 행 조회(3.3) — 기본 키 순서면 after·before로 키 기준 페이지 넘김을 한다(5.11) */
    public RowsResponse rows(long userId, String workspaceId, String connectionId, String objectName,
                             RowsRequest request) {
        int page = request == null || request.page() == null ? 1 : request.page();
        int size = request == null || request.size() == null ? limits.pageSizeDefault() : request.size();
        if (size > limits.pageSizeMax()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "size");
        }
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId);
        return limiter.run(userId, () -> withReadOnly(access, (connection, dialect, schema) -> {
            long start = System.nanoTime();
            TableStructure structure = catalogReader.structure(connection, dialect, schema, objectName);
            RowFilters filters = RowFilters.of(request == null ? null : request.filters(), structure, dialect,
                    limits.filtersMax());
            List<RowsRequest.Sort> sort = request == null ? null : request.sort();
            String orderBy = orderBy(sort, structure, dialect);
            Keyset keyset = Keyset.of(sort, structure, dialect,
                    request == null ? null : request.after(), request == null ? null : request.before());
            String where = filters.sql();
            if (keyset.condition() != null) {
                where = where.isEmpty() ? " WHERE " + keyset.condition() : where + " AND (" + keyset.condition() + ")";
            }
            String sql = "SELECT " + selectList(structure, dialect)
                    + " FROM " + dialect.qualified(schema, structure.name())
                    + where
                    + (keyset.backward() ? keyset.reversedOrderBy() : orderBy)
                    + (keyset.condition() != null ? " LIMIT ?" : " LIMIT ? OFFSET ?");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                targetDatabase.applyStatementTimeout(statement);
                int index = filters.bind(statement, 1);
                index = keyset.bind(statement, index);
                statement.setInt(index++, size + 1);            // 한 행 더 읽어 다음 페이지 유무만 판단한다
                if (keyset.condition() == null) {
                    statement.setLong(index, (long) (page - 1) * size);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    return readPage(rs, structure, keyset, page, size, start);
                }
            }
        }));
    }

    /** 정확한 행 수(3.4) */
    public CountResponse count(long userId, String workspaceId, String connectionId, String objectName,
                               CountRequest request) {
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId);
        return limiter.run(userId, () -> withReadOnly(access, (connection, dialect, schema) -> {
            long start = System.nanoTime();
            TableStructure structure = catalogReader.structure(connection, dialect, schema, objectName);
            RowFilters filters = RowFilters.of(request == null ? null : request.filters(), structure, dialect,
                    limits.filtersMax());
            String sql = "SELECT COUNT(*) FROM " + dialect.qualified(schema, structure.name()) + filters.sql();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                targetDatabase.applyStatementTimeout(statement);
                filters.bind(statement, 1);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return new CountResponse(Long.toString(rs.getLong(1)), elapsedMs(start));
                }
            }
        }));
    }

    private RowsResponse readPage(ResultSet rs, TableStructure structure, Keyset keyset, int page, int size,
                                  long start) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<List<Object>> rows = new ArrayList<>();
        List<Map<String, String>> keys = new ArrayList<>();
        boolean more = false;
        boolean truncated = false;
        long bytes = 0;
        while (rs.next()) {
            if (rows.size() == size) {
                more = true;                                      // size + 1번째 행 — 내보내지 않는다
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                Object cell = CellValues.read(rs, meta, i, limits.cellTextLength());
                bytes += CellValues.sizeOf(cell);
                row.add(cell);
            }
            if (bytes > limits.responseBytesMax() && !rows.isEmpty()) {
                truncated = true;                                 // 응답 크기 한도 — 여기서 자른다(Section 2.3)
                more = true;
                break;
            }
            rows.add(row);
            if (keyset.usable()) {
                keys.add(keyset.read(rs, meta, structure));
            }
        }
        boolean hasNext;
        boolean hasPrevious;
        if (keyset.backward()) {
            // 거꾸로 읽었다 — 화면 순서로 되돌린다. before 키의 행이 뒤에 있으므로 다음 페이지는 있다
            java.util.Collections.reverse(rows);
            java.util.Collections.reverse(keys);
            hasPrevious = more;
            hasNext = true;
        } else {
            hasNext = more;
            hasPrevious = keyset.condition() != null || page > 1;
        }
        List<ColumnMeta> columns = structure.columns().stream()
                .map(c -> new ColumnMeta(c.name(), c.typeName(), c.category(), c.nullable(), c.primaryKey(), c.generated()))
                .toList();
        Map<String, String> firstKey = keys.isEmpty() ? null : keys.get(0);
        Map<String, String> lastKey = keys.isEmpty() ? null : keys.get(keys.size() - 1);
        return new RowsResponse(columns, rows, page, size, hasNext, truncated, elapsedMs(start),
                hasPrevious, keyset.usable(), firstKey, lastKey);
    }

    private static String selectList(TableStructure structure, Dialect dialect) {
        return structure.columns().stream().map(c -> dialect.quote(c.name())).collect(Collectors.joining(", "));
    }

    /** 정렬 — 요청이 없으면 기본 키 오름차순, 기본 키도 없으면 정렬하지 않는다 */
    private String orderBy(List<RowsRequest.Sort> sort, TableStructure structure, Dialect dialect) {
        if (sort == null || sort.isEmpty()) {
            return structure.primaryKey().isEmpty() ? ""
                    : " ORDER BY " + structure.primaryKey().stream().map(dialect::quote).collect(Collectors.joining(", "));
        }
        if (sort.size() > limits.sortsMax()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "sort");
        }
        List<String> terms = new ArrayList<>();
        for (RowsRequest.Sort item : sort) {
            if (structure.column(item.column()) == null) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.filter.unknown-column", item.column());
            }
            terms.add(dialect.quote(item.column()) + (item.direction() == RowsRequest.Direction.DESC ? " DESC" : " ASC"));
        }
        return " ORDER BY " + String.join(", ", terms);
    }

    private <T> T withReadOnly(ConnectionAccess access, ReadWork<T> work) {
        Connection connection = targetDatabase.openReadOnly(access);
        try {
            Dialect dialect = targetDatabase.dialectOf(access);
            return work.run(connection, dialect, dialect.resolveSchema(connection, access));
        } catch (SQLException e) {
            throw SqlErrors.translateQuery(e);
        } finally {
            TargetDatabase.closeQuietly(connection);
        }
    }

    private String accessDetail(ConnectionAccess access) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workspaceId", access.workspaceId());
        detail.put("connectionId", access.connectionId());
        detail.put("connectionName", access.connectionName());
        return objectMapper.writeValueAsString(detail);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @FunctionalInterface
    private interface ReadWork<T> {
        T run(Connection connection, Dialect dialect, String schema) throws SQLException;
    }
}
