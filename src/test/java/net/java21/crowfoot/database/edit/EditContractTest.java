package net.java21.crowfoot.database.edit;

import net.java21.crowfoot.database.client.CoreClient;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import net.java21.crowfoot.database.edit.dto.CellRequest;
import net.java21.crowfoot.database.edit.dto.CellResponse;
import net.java21.crowfoot.database.edit.dto.ChangesRequest;
import net.java21.crowfoot.database.edit.dto.ChangesRequest.Change;
import net.java21.crowfoot.database.edit.dto.ChangesRequest.Op;
import net.java21.crowfoot.database.edit.dto.ChangesResponse;
import net.java21.crowfoot.database.edit.dto.SampleDataRequest;
import net.java21.crowfoot.database.edit.dto.SampleDataResponse;
import net.java21.crowfoot.database.jdbc.CatalogReader;
import net.java21.crowfoot.database.jdbc.MySqlDialect;
import net.java21.crowfoot.database.jdbc.PostgresDialect;
import net.java21.crowfoot.database.jdbc.TargetDatabase;
import net.java21.crowfoot.database.support.UserConcurrencyLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 행 편집·긴 값 읽기 계약 — MySQL·PostgreSQL 실물에서 같은 시나리오를 돌린다(00-data-browser.md Section 3.5·3.7).
 * 하위 클래스가 컨테이너를 댄다. 매 테스트는 notes 2행·tags 2행에서 시작한다.
 */
abstract class EditContractTest {

    static final long USER = 7L;
    static final String WORKSPACE = "34";
    static final String CONNECTION = "302";

    final List<String[]> audits = new ArrayList<>();
    EditService service;

    abstract ConnectionAccess access();

    /** 테스트 검증용 직접 접속(서비스를 거치지 않는다) */
    abstract Connection direct() throws Exception;

    /** 테이블을 처음 상태로 되돌린다 */
    abstract void resetData() throws Exception;

    static LimitsProperties limits(int valueLengthMax, int changesMax) {
        return new LimitsProperties(Duration.ofSeconds(5), Duration.ofSeconds(8), 100, 500, 500, 1000, 2000,
                valueLengthMax, 5 * 1024 * 1024, 10, 3, changesMax, 100_000, 2);
    }

    EditService serviceFor(LimitsProperties limits) {
        CoreClient core = new CoreClient() {
            @Override
            public ConnectionAccess requireAccess(long userId, String workspaceId, String connectionId) {
                return access();
            }

            @Override
            public void recordAuditLog(long actorId, String action, String detail) {
                audits.add(new String[] {Long.toString(actorId), action, detail});
            }
        };
        return new EditService(core, new TargetDatabase(List.of(new MySqlDialect(), new PostgresDialect()), limits),
                new CatalogReader(), new UserConcurrencyLimiter(limits), limits, JsonMapper.builder().build());
    }

    @BeforeEach
    void wire() throws Exception {
        resetData();
        audits.clear();
        service = serviceFor(limits(1_000_000, 100));
    }

    ChangesResponse apply(String object, Change... changes) {
        return service.apply(USER, WORKSPACE, CONNECTION, object, new ChangesRequest(List.of(changes)));
    }

    /** null 값을 담을 수 있는 맵 — Map.of는 null을 받지 않는다 */
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static Change insert(Map<String, Object> values) {
        return new Change(Op.INSERT, null, values, null);
    }

    static Change update(Map<String, Object> key, Map<String, Object> values, Map<String, Object> original) {
        return new Change(Op.UPDATE, key, values, original);
    }

    static Change delete(Map<String, Object> key) {
        return new Change(Op.DELETE, key, null, null);
    }

    /** 한 줄 조회 — 컬럼 값들을 문자열로(NULL은 null) */
    List<String> row(String sql) throws Exception {
        try (Connection connection = direct(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                values.add(rs.getString(i));
            }
            return values;
        }
    }

    String scalar(String sql) throws Exception {
        return row(sql).get(0);
    }

    static void assertChangeFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code, int index) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ChangeFailedException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(code);
            assertThat(ex.getIndex()).isEqualTo(index);
        });
    }

    static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("추가 — 빠진 컬럼은 기본값, null은 NULL. 생성된 기본 키를 돌려준다")
    void inserts() throws Exception {
        ChangesResponse response = apply("notes", insert(map("title", "새 메모", "body", null)));

        assertThat(response.inserted()).isEqualTo(1);
        assertThat(response.generatedKeys()).hasSize(1);
        String id = response.generatedKeys().get(0).key().get("id");
        assertThat(response.generatedKeys().get(0).index()).isZero();
        assertThat(id).isEqualTo("3");
        assertThat(row("SELECT title, body, qty FROM notes WHERE id = 3")).containsExactly("새 메모", null, null);
        // done은 요청에 없었다 — 기본값(거짓)이 들어간다
        assertThat(scalar("SELECT COUNT(*) FROM notes WHERE id = 3 AND done = " + falseLiteral())).isEqualTo("1");

        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[1]).isEqualTo("CONNECTION_ROWS_CHANGED");
        assertThat(audits.get(0)[2]).contains("\"object\":\"notes\"").contains("\"inserted\":1").contains("\"updated\":0")
                .contains("\"ok\":true").doesNotContain("새 메모");
    }

    abstract String falseLiteral();

    @Test
    @DisplayName("추가 — 기본 키를 요청이 주는 테이블(복합 키)은 그 값을 그대로 돌려준다")
    void insertsWithProvidedCompositeKey() throws Exception {
        ChangesResponse response = apply("tags", insert(map("note_id", "2", "tag", "urgent", "weight", "5")));

        assertThat(response.generatedKeys().get(0).key()).containsEntry("note_id", "2").containsEntry("tag", "urgent");
        assertThat(scalar("SELECT weight FROM tags WHERE note_id = 2 AND tag = 'urgent'")).isEqualTo("5");
    }

    @Test
    @DisplayName("수정 — 편집 전 값이 그대로일 때만 바꾼다. 불리언은 true·false 표기를 그대로 받는다")
    void updates() throws Exception {
        ChangesResponse response = apply("notes",
                update(map("id", "1"), map("title", "고친 제목", "done", "true"), map("title", "첫 메모", "done", "false")),
                update(map("id", "2"), map("body", null, "qty", "7"), map("body", "본문 2", "qty", null)));

        assertThat(response.updated()).isEqualTo(2);
        assertThat(scalar("SELECT title FROM notes WHERE id = 1")).isEqualTo("고친 제목");
        assertThat(scalar("SELECT COUNT(*) FROM notes WHERE id = 1 AND done = " + trueLiteral())).isEqualTo("1");
        assertThat(row("SELECT body, qty FROM notes WHERE id = 2")).containsExactly(null, "7");
    }

    abstract String trueLiteral();

    @Test
    @DisplayName("충돌 — 그 사이 값이 바뀌었으면 ROW_CONFLICT이고, 같은 요청의 앞선 변경까지 전부 되돌린다")
    void conflictRollsBackWholeBatch() throws Exception {
        assertChangeFailed(() -> apply("notes",
                insert(map("title", "함께 되돌려질 행")),
                update(map("id", "1"), map("title", "덮어쓰기"), map("title", "다른 사람이 이미 바꾼 값"))),
                ErrorCode.ROW_CONFLICT, 1);

        assertThat(scalar("SELECT COUNT(*) FROM notes")).isEqualTo("2");
        assertThat(scalar("SELECT title FROM notes WHERE id = 1")).isEqualTo("첫 메모");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[2]).contains("\"ok\":false").contains("\"inserted\":0");
    }

    @Test
    @DisplayName("삭제 — 기본 키로 한 행만 지운다. 없는 행은 ROW_CONFLICT")
    void deletes() throws Exception {
        assertThat(apply("tags", delete(map("note_id", "1", "tag", "work"))).deleted()).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM tags")).isEqualTo("1");

        assertChangeFailed(() -> apply("tags", delete(map("note_id", "9", "tag", "nope"))), ErrorCode.ROW_CONFLICT, 0);
    }

    @Test
    @DisplayName("기본 키도 고칠 수 있다 — 행은 편집 전 키로 찾는다")
    void updatesPrimaryKey() throws Exception {
        apply("tags", update(map("note_id", "1", "tag", "work"), map("tag", "office"), null));

        assertThat(scalar("SELECT COUNT(*) FROM tags WHERE note_id = 1 AND tag = 'office'")).isEqualTo("1");
        assertThat(scalar("SELECT COUNT(*) FROM tags WHERE tag = 'work'")).isEqualTo("0");
    }

    @Test
    @DisplayName("데이터베이스가 거부한 변경 — 몇 번째 변경인지와 데이터베이스 문구를 알리고 전부 되돌린다")
    void reportsDatabaseRejection() throws Exception {
        // NOT NULL 위반
        assertThatThrownBy(() -> apply("notes",
                update(map("id", "2"), map("qty", "1"), null),
                update(map("id", "1"), map("title", null), null)))
                .isInstanceOfSatisfying(ChangeFailedException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ROW_CHANGE_FAILED);
                    assertThat(ex.getIndex()).isEqualTo(1);
                    assertThat(ex.getDetail()).containsIgnoringCase("title");
                });
        assertThat(row("SELECT qty FROM notes WHERE id = 2")).containsExactly((String) null);

        // 중복 키
        assertChangeFailed(() -> apply("tags", insert(map("note_id", "1", "tag", "work"))), ErrorCode.ROW_CHANGE_FAILED, 0);
    }

    @Test
    @DisplayName("값이 컬럼 타입에 맞지 않으면 실패하고 되돌린다")
    void rejectsUnconvertibleValue() throws Exception {
        assertThatThrownBy(() -> apply("notes", update(map("id", "1"), map("qty", "열 개"), null)))
                .isInstanceOfSatisfying(ChangeFailedException.class, ex -> {
                    // PostgreSQL은 22 계열(INVALID_VALUE), MySQL 엄격 모드는 HY000(ROW_CHANGE_FAILED)으로 알린다
                    assertThat(ex.getErrorCode()).isIn(ErrorCode.INVALID_VALUE, ErrorCode.ROW_CHANGE_FAILED);
                    assertThat(ex.getIndex()).isZero();
                });
        assertThat(row("SELECT qty FROM notes WHERE id = 1")).containsExactly("10");
    }

    @Test
    @DisplayName("생성 컬럼 — 값을 주면 GENERATED_COLUMN으로 거부하고, 빼고 넣으면 데이터베이스가 계산한다")
    @SuppressWarnings("unchecked")
    void rejectsGeneratedColumn() throws Exception {
        assertThatThrownBy(() -> apply("priced", insert(map("id", "1", "qty", "2", "price", "3", "total", "6"))))
                .isInstanceOfSatisfying(ChangeFailedException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.GENERATED_COLUMN);
                    assertThat(ex.getIndex()).isZero();
                });
        assertThatThrownBy(() -> sample(true, table("priced", map("id", 1, "qty", 2, "price", 3, "total", 6))))
                .isInstanceOfSatisfying(ChangeFailedException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.GENERATED_COLUMN);
                    assertThat(ex.getField()).isEqualTo("tables[0].rows[0]");
                });

        apply("priced", insert(map("id", "1", "qty", "2", "price", "3")));
        assertThat(row("SELECT total FROM priced WHERE id = 1")).containsExactly("6");
        assertThatThrownBy(() -> apply("priced", update(map("id", "1"), map("total", "7"), null)))
                .isInstanceOfSatisfying(ChangeFailedException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.GENERATED_COLUMN));
    }

    @Test
    @DisplayName("기본값이 CURRENT_TIMESTAMP인 일반 컬럼은 생성 컬럼이 아니다 — 값을 지정해 넣는다(신고 43)")
    void currentTimestampDefaultIsNotGenerated() throws Exception {
        sample(false, table("priced", map("id", 2, "qty", 1, "price", 1, "stamped_at", "2026-09-01 10:00:00")));
        assertThat(row("SELECT stamped_at FROM priced WHERE id = 2").get(0)).startsWith("2026-09-01 10:00:00");

        apply("priced", update(map("id", "2"), map("stamped_at", "2026-09-02 11:00:00"), null));
        assertThat(row("SELECT stamped_at FROM priced WHERE id = 2").get(0)).startsWith("2026-09-02 11:00:00");
    }

    @Test
    @DisplayName("요청 검증 — 키 누락·없는 컬럼·이진 컬럼·객체 표기·빈 수정")
    void validatesChanges() throws Exception {
        assertChangeFailed(() -> apply("notes", update(null, map("title", "x"), null)), ErrorCode.INVALID_REQUEST, 0);
        assertChangeFailed(() -> apply("tags", delete(map("note_id", "1"))), ErrorCode.INVALID_REQUEST, 0);
        assertChangeFailed(() -> apply("notes", delete(map("id", "1", "title", "첫 메모"))), ErrorCode.INVALID_REQUEST, 0);
        assertChangeFailed(() -> apply("notes", insert(map("nope", "x"))), ErrorCode.INVALID_REQUEST, 0);
        assertChangeFailed(() -> apply("notes", insert(map("title\" = 'x'; DROP TABLE notes; --", "x"))),
                ErrorCode.INVALID_REQUEST, 0);
        assertChangeFailed(() -> apply("notes", update(map("id", "1"), map("attachment", "00ff"), null)),
                ErrorCode.INVALID_VALUE, 0);
        assertChangeFailed(() -> apply("notes",
                update(map("id", "1"), map("body", Map.of("truncated", true, "text", "앞부분", "length", 9999)), null)),
                ErrorCode.INVALID_VALUE, 0);
        assertChangeFailed(() -> apply("notes", update(map("id", "1"), Map.of(), null)), ErrorCode.INVALID_REQUEST, 0);
        assertChangeFailed(() -> apply("notes", delete(map("id", 1))), ErrorCode.INVALID_REQUEST, 0);
        assertThat(scalar("SELECT COUNT(*) FROM notes")).isEqualTo("2");
    }

    @Test
    @DisplayName("편집할 수 없는 객체 — 뷰와 기본 키 없는 테이블")
    void rejectsNonEditableObjects() {
        assertError(() -> apply("done_notes", insert(map("title", "x"))), ErrorCode.OBJECT_NOT_EDITABLE);
        assertError(() -> apply("note_logs", insert(map("message", "x"))), ErrorCode.OBJECT_NOT_EDITABLE);
        assertError(() -> apply("nope", insert(map("title", "x"))), ErrorCode.OBJECT_NOT_FOUND);
        assertThat(audits).isEmpty();
    }

    @Test
    @DisplayName("한도 — 변경 수와 값 길이")
    void enforcesLimits() {
        EditService tight = serviceFor(limits(10, 2));
        List<Change> three = Collections.nCopies(3, insert(map("title", "x")));
        assertError(() -> tight.apply(USER, WORKSPACE, CONNECTION, "notes", new ChangesRequest(three)), ErrorCode.INVALID_REQUEST);
        assertChangeFailed(() -> tight.apply(USER, WORKSPACE, CONNECTION, "notes",
                new ChangesRequest(List.of(insert(map("title", "열한 글자가 넘는 긴 제목"))))), ErrorCode.VALUE_TOO_LARGE, 0);
    }

    @Test
    @DisplayName("긴 값 읽기 — 잘려 내려오는 값을 통째로 읽는다. NULL은 null로, 없는 행은 ROW_CONFLICT")
    void readsWholeCell() {
        CellResponse whole = service.cell(USER, WORKSPACE, CONNECTION, "notes", new CellRequest(map("id", "1"), "body"));
        assertThat(whole.value()).hasSize(3000).startsWith("가나다");
        assertThat(whole.length()).isEqualTo(3000);

        // 문자가 아닌 컬럼도 문자 표현으로 읽는다
        assertThat(service.cell(USER, WORKSPACE, CONNECTION, "notes", new CellRequest(map("id", "1"), "qty")).value())
                .isEqualTo("10");
        CellResponse nullValue = service.cell(USER, WORKSPACE, CONNECTION, "notes", new CellRequest(map("id", "2"), "qty"));
        assertThat(nullValue.value()).isNull();
        assertThat(nullValue.length()).isZero();

        assertError(() -> service.cell(USER, WORKSPACE, CONNECTION, "notes", new CellRequest(map("id", "99"), "body")),
                ErrorCode.ROW_CONFLICT);
        assertError(() -> service.cell(USER, WORKSPACE, CONNECTION, "notes", new CellRequest(map("id", "1"), "attachment")),
                ErrorCode.INVALID_REQUEST);
        assertError(() -> service.cell(USER, WORKSPACE, CONNECTION, "notes", new CellRequest(map("id", "1"), "nope")),
                ErrorCode.INVALID_REQUEST);
        assertError(() -> service.cell(USER, WORKSPACE, CONNECTION, "done_notes", new CellRequest(map("id", "1"), "title")),
                ErrorCode.OBJECT_NOT_EDITABLE);
        // 읽기는 감사 기록을 남기지 않는다
        assertThat(audits).isEmpty();
    }

    @Test
    @DisplayName("긴 값 읽기 — 한도를 넘는 값은 읽지 않고 전체 길이만 알린다")
    void refusesOversizedCell() {
        EditService tight = serviceFor(limits(100, 100));

        assertThatThrownBy(() -> tight.cell(USER, WORKSPACE, CONNECTION, "notes", new CellRequest(map("id", "1"), "body")))
                .isInstanceOfSatisfying(ChangeFailedException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALUE_TOO_LARGE);
                    assertThat(ex.getDetail()).isEqualTo("3000");
                });
    }

    @Test
    @DisplayName("긴 값도 통째로 고칠 수 있다 — 전체 문자열을 값과 편집 전 값으로 보낸다")
    void editsWholeLongValue() throws Exception {
        String original = service.cell(USER, WORKSPACE, CONNECTION, "notes", new CellRequest(map("id", "1"), "body")).value();
        String edited = original + " — 덧붙임";

        apply("notes", update(map("id", "1"), map("body", edited), map("body", original)));

        assertThat(scalar("SELECT CHAR_LENGTH(body) FROM notes WHERE id = 1")).isEqualTo(Integer.toString(edited.length()));
    }

    /* ---------- 샘플 데이터 넣기 (3.8) ---------- */

    static SampleDataRequest.Table table(String name, Map<String, Object>... rows) {
        return new SampleDataRequest.Table(name, List.of(rows));
    }

    SampleDataResponse sample(boolean dryRun, SampleDataRequest.Table... tables) {
        return service.sampleData(USER, WORKSPACE, CONNECTION, new SampleDataRequest(dryRun, List.of(tables)), false, null);
    }

    @Test
    @DisplayName("샘플 데이터 — 여러 테이블에 적힌 순서대로 넣는다. 숫자와 불리언 값을 받고, 기본 키 없는 테이블에도 넣는다")
    @SuppressWarnings("unchecked")
    void sampleDataInserts() throws Exception {
        SampleDataResponse response = sample(false,
                table("notes", map("id", 10, "title", "샘플 1", "done", true, "qty", 3), map("id", 11, "title", "샘플 2", "body", null)),
                table("tags", map("note_id", 10, "tag", "sample", "weight", "7")),
                table("note_logs", map("message", "샘플 로그")));

        assertThat(response.dryRun()).isFalse();
        assertThat(response.inserted()).isEqualTo(4);
        assertThat(response.tables()).extracting(SampleDataResponse.TableCount::name).containsExactly("notes", "tags", "note_logs");
        assertThat(response.tables()).extracting(SampleDataResponse.TableCount::inserted).containsExactly(2, 1, 1);
        assertThat(row("SELECT title, qty FROM notes WHERE id = 10")).containsExactly("샘플 1", "3");
        assertThat(scalar("SELECT COUNT(*) FROM notes WHERE id = 10 AND done = " + trueLiteral())).isEqualTo("1");
        assertThat(scalar("SELECT weight FROM tags WHERE note_id = 10")).isEqualTo("7");
        assertThat(scalar("SELECT COUNT(*) FROM note_logs")).isEqualTo("1");
        // 감사 기록 — 테이블 이름과 행 수만 남긴다. 값은 남기지 않는다
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[1]).isEqualTo("CONNECTION_SAMPLE_DATA_INSERTED");
        assertThat(audits.get(0)[2]).contains("\"inserted\":4").contains("\"ok\":true").doesNotContain("샘플 1");
    }

    @Test
    @DisplayName("샘플 데이터 — dryRun은 넣어 본 뒤 전부 되돌린다. 남는 행이 없고 감사 기록도 남기지 않는다")
    @SuppressWarnings("unchecked")
    void sampleDataDryRunLeavesNothing() throws Exception {
        SampleDataResponse response = sample(true,
                table("notes", map("id", 10, "title", "샘플 1")),
                table("tags", map("note_id", 10, "tag", "sample")));

        assertThat(response.dryRun()).isTrue();
        assertThat(response.inserted()).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM notes")).isEqualTo("2");
        assertThat(scalar("SELECT COUNT(*) FROM tags")).isEqualTo("2");
        assertThat(audits).isEmpty();
    }

    @Test
    @DisplayName("샘플 데이터 — 하나라도 실패하면 전부 되돌리고 어느 행인지 알린다")
    @SuppressWarnings("unchecked")
    void sampleDataRollsBackOnFailure() throws Exception {
        assertThatThrownBy(() -> sample(false,
                table("notes", map("id", 10, "title", "샘플 1")),
                // 기본 키가 겹친다(1, 'work'는 이미 있다)
                table("tags", map("note_id", 10, "tag", "ok"), map("note_id", 1, "tag", "work"))))
                .isInstanceOfSatisfying(ChangeFailedException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ROW_CHANGE_FAILED);
                    assertThat(ex.getField()).isEqualTo("tables[1].rows[1]");
                });
        assertThat(scalar("SELECT COUNT(*) FROM notes")).isEqualTo("2");
        assertThat(scalar("SELECT COUNT(*) FROM tags")).isEqualTo("2");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[2]).contains("\"ok\":false");
    }

    @Test
    @DisplayName("샘플 데이터 — 없는 테이블, 없는 컬럼, 뷰는 거부한다")
    @SuppressWarnings("unchecked")
    void sampleDataRejectsUnknownTargets() {
        assertError(() -> sample(true, table("no_such_table", map("a", 1))), ErrorCode.OBJECT_NOT_FOUND);
        assertError(() -> sample(true, table("done_notes", map("id", 1, "title", "x"))), ErrorCode.OBJECT_NOT_EDITABLE);
        assertThatThrownBy(() -> sample(true, table("notes", map("title", "x", "no_such_column", 1))))
                .isInstanceOfSatisfying(ChangeFailedException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ex.getField()).isEqualTo("tables[0].rows[0]");
                });
    }

    @Test
    @DisplayName("샘플 데이터 — 한 번에 테이블 20개, 행 1,000개까지다")
    @SuppressWarnings("unchecked")
    void sampleDataLimits() {
        Map<String, Object>[] many = new Map[1001];
        java.util.Arrays.fill(many, map("message", "x"));
        assertError(() -> sample(true, table("note_logs", many)), ErrorCode.INVALID_REQUEST);
        SampleDataRequest.Table[] tables = new SampleDataRequest.Table[21];
        java.util.Arrays.fill(tables, table("note_logs", map("message", "x")));
        assertError(() -> sample(true, tables), ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("샘플 데이터 — MCP로 온 요청이면 접근 확인에 mcpWrite를 켠다")
    @SuppressWarnings("unchecked")
    void sampleDataAsksMcpWriteForTokenRequests() {
        List<Boolean> asked = new ArrayList<>();
        CoreClient core = new CoreClient() {
            @Override
            public ConnectionAccess requireAccess(long userId, String workspaceId, String connectionId) {
                return access();
            }

            @Override
            public ConnectionAccess requireAccess(long userId, String workspaceId, String connectionId, boolean mcpWrite) {
                asked.add(mcpWrite);
                return access();
            }

            @Override
            public void recordAuditLog(long actorId, String action, String detail) {
                audits.add(new String[] {Long.toString(actorId), action, detail});
            }
        };
        LimitsProperties limits = limits(1_000_000, 100);
        EditService viaCore = new EditService(core, new TargetDatabase(List.of(new MySqlDialect(), new PostgresDialect()), limits),
                new CatalogReader(), new UserConcurrencyLimiter(limits), limits, JsonMapper.builder().build());
        SampleDataRequest request = new SampleDataRequest(false, List.of(table("note_logs", map("message", "x"))));

        viaCore.sampleData(USER, WORKSPACE, CONNECTION, request, true, "12");
        viaCore.sampleData(USER, WORKSPACE, CONNECTION, request, false, null);

        assertThat(asked).containsExactly(true, false);
        assertThat(audits.get(0)[2]).contains("\"tokenId\":\"12\"");
        assertThat(audits.get(1)[2]).doesNotContain("tokenId");
    }

    static String longText() {
        return "가나다라마바사아자차".repeat(300);
    }
}
