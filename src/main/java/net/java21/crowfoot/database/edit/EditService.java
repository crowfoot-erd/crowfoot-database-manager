package net.java21.crowfoot.database.edit;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.database.client.CoreClient;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import net.java21.crowfoot.database.edit.dto.CellRequest;
import net.java21.crowfoot.database.edit.dto.CellResponse;
import net.java21.crowfoot.database.edit.dto.ChangesRequest;
import net.java21.crowfoot.database.edit.dto.ChangesRequest.Change;
import net.java21.crowfoot.database.edit.dto.ChangesResponse;
import net.java21.crowfoot.database.edit.dto.SampleDataRequest;
import net.java21.crowfoot.database.edit.dto.SampleDataResponse;
import net.java21.crowfoot.database.jdbc.CatalogReader;
import net.java21.crowfoot.database.jdbc.Dialect;
import net.java21.crowfoot.database.jdbc.SqlErrors;
import net.java21.crowfoot.database.jdbc.TableStructure;
import net.java21.crowfoot.database.jdbc.TargetDatabase;
import net.java21.crowfoot.database.jdbc.ValueBinder;
import net.java21.crowfoot.database.support.UserConcurrencyLimiter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 행 편집과 긴 값 읽기 (00-data-browser.md Section 3.5·3.7).
 *
 * <p>클라이언트는 "어느 행의 어느 컬럼을 무슨 값으로"만 보낸다. SQL은 이 서비스가 만든다 —
 * 식별자는 카탈로그에서 읽은 이름만 인용해 쓰고, 값은 전부 바인딩 파라미터로 넣는다(Section 1.2 원칙 4).
 * 요청 하나가 트랜잭션 하나다. 하나라도 실패하면 전부 되돌린다.
 */
@Service
@RequiredArgsConstructor
public class EditService {

    static final String ACTION_ROWS_CHANGED = "CONNECTION_ROWS_CHANGED";
    static final String ACTION_SAMPLE_DATA_INSERTED = "CONNECTION_SAMPLE_DATA_INSERTED";
    /** 샘플 데이터 한 번의 한도 (00-data-browser.md Section 2.3) */
    static final int SAMPLE_TABLES_MAX = 20;
    static final int SAMPLE_ROWS_MAX = 1000;

    private final CoreClient coreClient;
    private final TargetDatabase targetDatabase;
    private final CatalogReader catalogReader;
    private final UserConcurrencyLimiter limiter;
    private final LimitsProperties limits;
    private final ObjectMapper objectMapper;

    /** 행 편집 적용(3.5) */
    public ChangesResponse apply(long userId, String workspaceId, String connectionId, String objectName,
                                 ChangesRequest request) {
        if (request.changes().size() > limits.changesMax()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "changes");
        }
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId);
        try {
            ChangesResponse response = limiter.run(userId, () -> applyInTransaction(access, objectName, request.changes()));
            coreClient.recordAuditLog(userId, ACTION_ROWS_CHANGED,
                    auditDetail(access, objectName, response.inserted(), response.updated(), response.deleted(), true));
            return response;
        } catch (ChangeFailedException e) {
            // 데이터베이스까지 간 시도는 실패도 남긴다 — 전부 되돌렸으므로 건수는 0이다
            coreClient.recordAuditLog(userId, ACTION_ROWS_CHANGED, auditDetail(access, objectName, 0, 0, 0, false));
            throw e;
        }
    }

    /**
     * 샘플 데이터 넣기(3.8) — 여러 테이블에 행을 넣는다. 요청 하나가 트랜잭션 하나이고, dryRun이면 넣어 본 뒤 전부 되돌린다.
     *
     * @param viaToken 워크스페이스 액세스 토큰(MCP)으로 온 요청 — MCP 반영을 허용한 커넥션에만 통과한다(core가 판정)
     */
    public SampleDataResponse sampleData(long userId, String workspaceId, String connectionId, SampleDataRequest request,
                                         boolean viaToken, String tokenId) {
        int rows = request.tables().stream().mapToInt(table -> table.rows().size()).sum();
        if (request.tables().size() > SAMPLE_TABLES_MAX) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "tables");
        }
        if (rows > SAMPLE_ROWS_MAX) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.request.limit", "rows");
        }
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId, viaToken);
        try {
            SampleDataResponse response = limiter.run(userId, () -> insertSampleData(access, request.tables(), dryRun));
            if (!dryRun) {
                coreClient.recordAuditLog(userId, ACTION_SAMPLE_DATA_INSERTED, sampleAuditDetail(access, response, true, tokenId));
            }
            return response;
        } catch (ChangeFailedException e) {
            if (!dryRun) {
                coreClient.recordAuditLog(userId, ACTION_SAMPLE_DATA_INSERTED, sampleAuditDetail(access, null, false, tokenId));
            }
            throw e;
        }
    }

    private SampleDataResponse insertSampleData(ConnectionAccess access, List<SampleDataRequest.Table> tables, boolean dryRun) {
        long start = System.nanoTime();
        Connection connection = targetDatabase.openWritable(access);
        try {
            connection.setAutoCommit(false);
            Dialect dialect = targetDatabase.dialectOf(access);
            String schema = dialect.resolveSchema(connection, access);
            List<SampleDataResponse.TableCount> counts = new ArrayList<>();
            int total = 0;
            for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
                SampleDataRequest.Table table = tables.get(tableIndex);
                TableStructure structure = catalogReader.structure(connection, dialect, schema, table.name());
                if (structure.view()) {
                    throw new BusinessException(ErrorCode.OBJECT_NOT_EDITABLE);
                }
                String qualified = dialect.qualified(schema, structure.name());
                for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
                    String field = "tables[" + tableIndex + "].rows[" + rowIndex + "]";
                    try {
                        insertSampleRow(connection, dialect, qualified, structure, table.rows().get(rowIndex), field);
                    } catch (SQLException e) {
                        if (SqlErrors.isTimeout(e)) {
                            throw new BusinessException(ErrorCode.QUERY_TIMEOUT);
                        }
                        throw new ChangeFailedException(
                                SqlErrors.isInvalidValue(e) ? ErrorCode.INVALID_VALUE : ErrorCode.ROW_CHANGE_FAILED,
                                rowIndex, SqlErrors.messageOf(e), field);
                    }
                }
                counts.add(new SampleDataResponse.TableCount(structure.name(), table.rows().size()));
                total += table.rows().size();
            }
            if (dryRun) {
                connection.rollback();
            } else {
                connection.commit();
            }
            return new SampleDataResponse(dryRun, total, counts, elapsedMs(start));
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw SqlErrors.translateQuery(e);
        } catch (RuntimeException e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            TargetDatabase.closeQuietly(connection);
        }
    }

    /** 샘플 행 하나 — 값은 문자열·숫자·불리언·null을 받는다(숫자와 불리언은 문자열 표기로 바꿔 타입에 맞게 넣는다) */
    private void insertSampleRow(Connection connection, Dialect dialect, String table, TableStructure structure,
                                 Map<String, Object> row, String field) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            TableStructure.Column column = structure.column(entry.getKey());
            if (column == null) {
                throw new ChangeFailedException(ErrorCode.INVALID_REQUEST, 0, entry.getKey(), field);
            }
            if (column.generated()) {
                throw new ChangeFailedException(ErrorCode.GENERATED_COLUMN, 0, column.name(), field);
            }
            Object value = entry.getValue();
            if ("binary".equals(column.category())
                    || !(value == null || value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new ChangeFailedException(ErrorCode.INVALID_VALUE, 0, column.name(), field);
            }
            String text = value == null ? null : String.valueOf(value);
            if (text != null && text.length() > limits.valueLengthMax()) {
                throw new ChangeFailedException(ErrorCode.VALUE_TOO_LARGE, 0, column.name(), field);
            }
            values.put(column.name(), text);
        }
        String sql = values.isEmpty()
                ? dialect.insertDefaults(table)
                : "INSERT INTO " + table + " ("
                + values.keySet().stream().map(dialect::quote).collect(Collectors.joining(", "))
                + ") VALUES (" + "?, ".repeat(values.size() - 1) + "?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            targetDatabase.applyStatementTimeout(statement);
            int parameter = 1;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                ValueBinder.bind(statement, parameter++, entry.getValue(), structure.column(entry.getKey()).category());
            }
            statement.executeUpdate();
        }
    }

    private String sampleAuditDetail(ConnectionAccess access, SampleDataResponse response, boolean ok, String tokenId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workspaceId", access.workspaceId());
        detail.put("connectionId", access.connectionId());
        // 데이터 값은 남기지 않는다 — 테이블 이름과 행 수만 남긴다(2.5)
        detail.put("tables", response == null ? List.of() : response.tables());
        detail.put("inserted", response == null ? 0 : response.inserted());
        detail.put("ok", ok);
        if (tokenId != null) {
            detail.put("tokenId", tokenId);
        }
        return objectMapper.writeValueAsString(detail);
    }

    /** 긴 값 읽기(3.7) */
    public CellResponse cell(long userId, String workspaceId, String connectionId, String objectName,
                             CellRequest request) {
        ConnectionAccess access = coreClient.requireAccess(userId, workspaceId, connectionId);
        return limiter.run(userId, () -> {
            long start = System.nanoTime();
            Connection connection = targetDatabase.openReadOnly(access);
            try {
                Dialect dialect = targetDatabase.dialectOf(access);
                String schema = dialect.resolveSchema(connection, access);
                TableStructure structure = requireEditable(catalogReader.structure(connection, dialect, schema, objectName));
                TableStructure.Column column = structure.column(request.column());
                if (column == null || "binary".equals(column.category())) {
                    throw BusinessException.of(ErrorCode.INVALID_REQUEST, "detail.filter.unknown-column", request.column());
                }
                Map<String, String> key = keyOf(structure, request.key(), -1);
                String quoted = dialect.quote(column.name());
                String text = dialect.castToText(quoted);
                // 길이를 먼저 본다 — 한도를 넘는 값을 메모리에 올리지 않는다
                String sql = "SELECT CHAR_LENGTH(" + text + "), CASE WHEN CHAR_LENGTH(" + text + ") <= ? THEN " + text
                        + " END FROM " + dialect.qualified(schema, structure.name()) + whereKey(structure, dialect);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    targetDatabase.applyStatementTimeout(statement);
                    statement.setInt(1, limits.valueLengthMax());
                    bindKey(statement, 2, structure, key);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (!rs.next()) {
                            throw new BusinessException(ErrorCode.ROW_CONFLICT);
                        }
                        long length = rs.getLong(1);
                        if (rs.wasNull()) {
                            return new CellResponse(column.name(), null, 0, elapsedMs(start));
                        }
                        if (length > limits.valueLengthMax()) {
                            throw new ChangeFailedException(ErrorCode.VALUE_TOO_LARGE, 0, Long.toString(length));
                        }
                        return new CellResponse(column.name(), rs.getString(2), length, elapsedMs(start));
                    }
                }
            } catch (SQLException e) {
                throw SqlErrors.translateQuery(e);
            } finally {
                TargetDatabase.closeQuietly(connection);
            }
        });
    }

    private ChangesResponse applyInTransaction(ConnectionAccess access, String objectName, List<Change> changes) {
        long start = System.nanoTime();
        Connection connection = targetDatabase.openWritable(access);
        try {
            connection.setAutoCommit(false);
            Dialect dialect = targetDatabase.dialectOf(access);
            String schema = dialect.resolveSchema(connection, access);
            TableStructure structure = requireEditable(catalogReader.structure(connection, dialect, schema, objectName));
            String table = dialect.qualified(schema, structure.name());

            int inserted = 0;
            int updated = 0;
            int deleted = 0;
            List<ChangesResponse.GeneratedKey> generatedKeys = new ArrayList<>();
            for (int index = 0; index < changes.size(); index++) {
                Change change = changes.get(index);
                try {
                    switch (change.op()) {
                        case INSERT -> {
                            generatedKeys.add(new ChangesResponse.GeneratedKey(index,
                                    insert(connection, dialect, table, structure, change, index)));
                            inserted++;
                        }
                        case UPDATE -> {
                            update(connection, dialect, table, structure, change, index);
                            updated++;
                        }
                        case DELETE -> {
                            delete(connection, dialect, table, structure, change, index);
                            deleted++;
                        }
                    }
                } catch (SQLException e) {
                    if (SqlErrors.isTimeout(e)) {
                        throw new BusinessException(ErrorCode.QUERY_TIMEOUT);
                    }
                    // 값 변환 실패(22 계열)와 그 밖의 거부(제약 위반·권한 없음 등)를 나눠 알린다
                    throw new ChangeFailedException(
                            SqlErrors.isInvalidValue(e) ? ErrorCode.INVALID_VALUE : ErrorCode.ROW_CHANGE_FAILED,
                            index, SqlErrors.messageOf(e));
                }
            }
            connection.commit();
            return new ChangesResponse(inserted, updated, deleted, generatedKeys, elapsedMs(start));
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw SqlErrors.translateQuery(e);
        } catch (RuntimeException e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            TargetDatabase.closeQuietly(connection);
        }
    }

    private Map<String, String> insert(Connection connection, Dialect dialect, String table, TableStructure structure,
                                       Change change, int index) throws SQLException {
        Map<String, String> values = valuesOf(structure, change.values(), index);
        String sql = values.isEmpty()
                ? dialect.insertDefaults(table)
                : "INSERT INTO " + table + " ("
                + values.keySet().stream().map(dialect::quote).collect(Collectors.joining(", "))
                + ") VALUES (" + "?, ".repeat(values.size() - 1) + "?)";
        // 기본 키 컬럼 이름을 넘겨 생성된 키를 돌려받는다(자동 증가·기본값으로 채워진 키)
        try (PreparedStatement statement = connection.prepareStatement(sql, structure.primaryKey().toArray(String[]::new))) {
            targetDatabase.applyStatementTimeout(statement);
            int parameter = 1;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                ValueBinder.bind(statement, parameter++, entry.getValue(), structure.column(entry.getKey()).category());
            }
            statement.executeUpdate();
            Map<String, String> key = new LinkedHashMap<>();
            try (ResultSet generated = statement.getGeneratedKeys()) {
                boolean hasGenerated = generated.next();
                int generatedColumns = hasGenerated ? generated.getMetaData().getColumnCount() : 0;
                int generatedIndex = 1;
                for (String column : structure.primaryKey()) {
                    if (values.get(column) != null) {
                        key.put(column, values.get(column));        // 요청이 준 키
                    } else if (hasGenerated && generatedIndex <= generatedColumns) {
                        key.put(column, generated.getString(generatedIndex++));
                    }
                }
            }
            return key;
        }
    }

    private void update(Connection connection, Dialect dialect, String table, TableStructure structure,
                        Change change, int index) throws SQLException {
        Map<String, String> key = keyOf(structure, change.key(), index);
        Map<String, String> values = valuesOf(structure, change.values(), index);
        Map<String, String> original = valuesOf(structure, change.original(), index);
        if (values.isEmpty()) {
            throw new ChangeFailedException(ErrorCode.INVALID_REQUEST, index, null);
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ")
                .append(values.keySet().stream().map(c -> dialect.quote(c) + " = ?").collect(Collectors.joining(", ")))
                .append(whereKey(structure, dialect));
        // 편집 전 값이 그대로일 때만 바꾼다 — 그 사이 다른 사람이 바꿨으면 영향 행이 0이 된다
        for (String column : original.keySet()) {
            sql.append(" AND ").append(dialect.nullSafeEquals(dialect.quote(column)));
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            targetDatabase.applyStatementTimeout(statement);
            int parameter = 1;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                ValueBinder.bind(statement, parameter++, entry.getValue(), structure.column(entry.getKey()).category());
            }
            parameter = bindKey(statement, parameter, structure, key);
            for (Map.Entry<String, String> entry : original.entrySet()) {
                ValueBinder.bind(statement, parameter++, entry.getValue(), structure.column(entry.getKey()).category());
            }
            requireOneRow(statement.executeUpdate(), index);
        }
    }

    private void delete(Connection connection, Dialect dialect, String table, TableStructure structure,
                        Change change, int index) throws SQLException {
        Map<String, String> key = keyOf(structure, change.key(), index);
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + whereKey(structure, dialect))) {
            targetDatabase.applyStatementTimeout(statement);
            bindKey(statement, 1, structure, key);
            requireOneRow(statement.executeUpdate(), index);
        }
    }

    /** 기본 키로 한 행만 겨냥한다 — 영향받은 행이 1이 아니면 대상이 없거나 그 사이에 바뀐 것이다 */
    private static void requireOneRow(int affected, int index) {
        if (affected != 1) {
            throw new ChangeFailedException(ErrorCode.ROW_CONFLICT, index, null);
        }
    }

    private static TableStructure requireEditable(TableStructure structure) {
        if (!structure.editable()) {
            throw new BusinessException(ErrorCode.OBJECT_NOT_EDITABLE);
        }
        return structure;
    }

    private static String whereKey(TableStructure structure, Dialect dialect) {
        return " WHERE " + structure.primaryKey().stream()
                .map(column -> dialect.quote(column) + " = ?")
                .collect(Collectors.joining(" AND "));
    }

    private static int bindKey(PreparedStatement statement, int startIndex, TableStructure structure,
                               Map<String, String> key) throws SQLException {
        int index = startIndex;
        for (String column : structure.primaryKey()) {
            ValueBinder.bind(statement, index++, key.get(column), structure.column(column).category());
        }
        return index;
    }

    /** 기본 키 값 — 기본 키 컬럼을 빠짐없이, 그것만 담아야 한다. 값은 null이 아닌 문자열이다 */
    private Map<String, String> keyOf(TableStructure structure, Map<String, Object> key, int index) {
        if (key == null || !key.keySet().equals(Set.copyOf(structure.primaryKey()))) {
            throw failRequest(index);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String column : structure.primaryKey()) {
            if (!(key.get(column) instanceof String value)) {
                throw failRequest(index);
            }
            result.put(column, value);
        }
        return result;
    }

    /** 컬럼 → 값(문자열 또는 null). 없는 컬럼·이진 컬럼·객체 표기(잘린 셀·이진 셀)·한도를 넘는 값은 거부한다 */
    private Map<String, String> valuesOf(TableStructure structure, Map<String, Object> values, int index) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            TableStructure.Column column = structure.column(entry.getKey());
            if (column == null) {
                throw failRequest(index);
            }
            if (column.generated()) {
                throw new ChangeFailedException(ErrorCode.GENERATED_COLUMN, Math.max(index, 0), column.name());
            }
            Object value = entry.getValue();
            if ("binary".equals(column.category()) || !(value == null || value instanceof String)) {
                throw new ChangeFailedException(ErrorCode.INVALID_VALUE, Math.max(index, 0), column.name());
            }
            if (value instanceof String text && text.length() > limits.valueLengthMax()) {
                throw new ChangeFailedException(ErrorCode.VALUE_TOO_LARGE, Math.max(index, 0), column.name());
            }
            result.put(column.name(), (String) value);
        }
        return result;
    }

    private static BusinessException failRequest(int index) {
        return index < 0
                ? new BusinessException(ErrorCode.INVALID_REQUEST)
                : new ChangeFailedException(ErrorCode.INVALID_REQUEST, index, null);
    }

    private String auditDetail(ConnectionAccess access, String objectName, int inserted, int updated, int deleted,
                               boolean ok) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workspaceId", access.workspaceId());
        detail.put("connectionId", access.connectionId());
        detail.put("object", objectName);
        detail.put("inserted", inserted);
        detail.put("updated", updated);
        detail.put("deleted", deleted);
        detail.put("ok", ok);
        return objectMapper.writeValueAsString(detail);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 되돌리기 실패 — 접속을 닫으면 데이터베이스가 미완료 트랜잭션을 버린다
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
