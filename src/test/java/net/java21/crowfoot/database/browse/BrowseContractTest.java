package net.java21.crowfoot.database.browse;

import net.java21.crowfoot.database.browse.dto.CountRequest;
import net.java21.crowfoot.database.browse.dto.ObjectsResponse;
import net.java21.crowfoot.database.browse.dto.RowsRequest;
import net.java21.crowfoot.database.browse.dto.RowsRequest.Direction;
import net.java21.crowfoot.database.browse.dto.RowsRequest.Filter;
import net.java21.crowfoot.database.browse.dto.RowsRequest.Op;
import net.java21.crowfoot.database.browse.dto.RowsRequest.Sort;
import net.java21.crowfoot.database.browse.dto.RowsResponse;
import net.java21.crowfoot.database.browse.dto.StructureResponse;
import net.java21.crowfoot.database.client.CoreClient;
import net.java21.crowfoot.database.client.dto.ConnectionAccess;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import net.java21.crowfoot.database.jdbc.CatalogReader;
import net.java21.crowfoot.database.jdbc.ColumnMeta;
import net.java21.crowfoot.database.jdbc.MySqlDialect;
import net.java21.crowfoot.database.jdbc.PostgresDialect;
import net.java21.crowfoot.database.jdbc.TargetDatabase;
import net.java21.crowfoot.database.support.UserConcurrencyLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 데이터 조회 계약 — MySQL·PostgreSQL 실물에서 같은 시나리오를 돌린다(00-data-browser.md Section 2.2·3.1~3.4).
 * 하위 클래스가 컨테이너와 접속 정보를 댄다. core는 가짜(접근 확인은 항상 통과, 감사 기록은 목록에 쌓는다).
 */
abstract class BrowseContractTest {

    static final long USER = 7L;
    static final String WORKSPACE = "34";
    static final String CONNECTION = "302";

    static final LimitsProperties LIMITS = new LimitsProperties(
            Duration.ofSeconds(5), Duration.ofSeconds(8), 100, 500, 500, 1000, 2000, 1_000_000,
            5 * 1024 * 1024, 10, 3, 100, 100_000, 2);

    final List<String[]> audits = new ArrayList<>();
    TargetDatabase targetDatabase;
    BrowseService service;

    /** 컨테이너를 가리키는 접속 정보 */
    abstract ConnectionAccess access();

    /** 십진 타입의 표시 이름 — MySQL DECIMAL, PostgreSQL NUMERIC */
    abstract String decimalTypeName();

    @BeforeEach
    void wire() {
        audits.clear();
        service = serviceFor(LIMITS, access());
    }

    BrowseService serviceFor(LimitsProperties limits, ConnectionAccess access) {
        CoreClient core = new CoreClient() {
            @Override
            public ConnectionAccess requireAccess(long userId, String workspaceId, String connectionId) {
                return access;
            }

            @Override
            public void recordAuditLog(long actorId, String action, String detail) {
                audits.add(new String[] {Long.toString(actorId), action, detail});
            }
        };
        targetDatabase = new TargetDatabase(List.of(new MySqlDialect(), new PostgresDialect()), limits);
        return new BrowseService(core, targetDatabase, new CatalogReader(), new UserConcurrencyLimiter(limits),
                limits, JsonMapper.builder().build());
    }

    private RowsResponse rows(String object, RowsRequest request) {
        return service.rows(USER, WORKSPACE, CONNECTION, object, request);
    }

    private static int indexOf(RowsResponse response, String column) {
        List<String> names = response.columns().stream().map(ColumnMeta::name).toList();
        assertThat(names).contains(column);
        return names.indexOf(column);
    }

    private static Object cell(RowsResponse response, int row, String column) {
        return response.rows().get(row).get(indexOf(response, column));
    }

    @Test
    @DisplayName("객체 목록 — 테이블·뷰를 이름순으로, 편집 가능 여부는 기본 키 유무로. 접근 사실을 감사 기록으로 남긴다")
    void listsObjects() {
        ObjectsResponse response = service.objects(USER, WORKSPACE, CONNECTION);

        assertThat(response.dbmsType()).isEqualTo(access().dbmsType());
        assertThat(response.objects()).extracting(ObjectsResponse.Item::name)
                .containsExactly("orderXlogs", "order_logs", "orders", "paid_orders", "users");
        Map<String, ObjectsResponse.Item> byName = new java.util.HashMap<>();
        response.objects().forEach(item -> byName.put(item.name(), item));
        assertThat(byName.get("users").kind()).isEqualTo("TABLE");
        assertThat(byName.get("users").editable()).isTrue();
        assertThat(byName.get("users").comment()).isEqualTo("회원");
        assertThat(byName.get("order_logs").editable()).as("기본 키 없는 테이블").isFalse();
        assertThat(byName.get("paid_orders").kind()).isEqualTo("VIEW");
        assertThat(byName.get("paid_orders").editable()).isFalse();
        assertThat(byName.get("paid_orders").estimatedRows()).isNull();

        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)[0]).isEqualTo("7");
        assertThat(audits.get(0)[1]).isEqualTo("CONNECTION_DATA_ACCESSED");
        assertThat(audits.get(0)[2]).contains("\"connectionId\":\"302\"").contains("\"workspaceId\":\"34\"")
                .doesNotContain(access().password());
    }

    @Test
    @DisplayName("구조 보기 — 컬럼 타입 표기·기본 키·인덱스·외래 키")
    void readsStructure() {
        StructureResponse users = service.structure(USER, WORKSPACE, CONNECTION, "users");

        assertThat(users.kind()).isEqualTo("TABLE");
        assertThat(users.editable()).isTrue();
        assertThat(users.primaryKey()).containsExactly("id");
        Map<String, StructureResponse.Column> columns = new java.util.HashMap<>();
        users.columns().forEach(column -> columns.put(column.name(), column));
        assertThat(columns.get("id").typeName()).isEqualTo("BIGINT");
        assertThat(columns.get("id").category()).isEqualTo("integer");
        assertThat(columns.get("id").primaryKey()).isTrue();
        assertThat(columns.get("id").autoIncrement()).isTrue();
        assertThat(columns.get("id").nullable()).isFalse();
        assertThat(columns.get("email").typeName()).isEqualTo("VARCHAR(191)");
        assertThat(columns.get("email").category()).isEqualTo("character");
        assertThat(columns.get("name").nullable()).isTrue();
        assertThat(columns.get("balance").typeName()).isEqualTo(decimalTypeName() + "(20,2)");
        assertThat(columns.get("balance").category()).isEqualTo("decimal");
        assertThat(columns.get("is_active").category()).isEqualTo("boolean");
        assertThat(columns.get("bio").category()).isEqualTo("text");
        assertThat(columns.get("avatar").category()).isEqualTo("binary");
        assertThat(columns.get("created_at").category()).isEqualTo("datetime");
        assertThat(users.indexes()).anySatisfy(index -> {
            assertThat(index.name()).isEqualTo("uk_users_email");
            assertThat(index.unique()).isTrue();
            assertThat(index.columns()).containsExactly("email");
        });

        StructureResponse orders = service.structure(USER, WORKSPACE, CONNECTION, "orders");
        assertThat(orders.foreignKeys()).singleElement().satisfies(fk -> {
            assertThat(fk.name()).isEqualTo("fk_orders_user_id");
            assertThat(fk.columns()).containsExactly("user_id");
            assertThat(fk.referencedObject()).isEqualTo("users");
            assertThat(fk.referencedColumns()).containsExactly("id");
        });
        assertThat(orders.indexes()).anySatisfy(index -> {
            assertThat(index.name()).isEqualTo("idx_orders_user_id");
            assertThat(index.unique()).isFalse();
        });
    }

    @Test
    @DisplayName("구조 보기 — 이름의 밑줄이 와일드카드로 풀려 다른 테이블의 컬럼이 섞이지 않는다")
    void doesNotLeakColumnsAcrossSimilarNames() {
        StructureResponse logs = service.structure(USER, WORKSPACE, CONNECTION, "order_logs");

        assertThat(logs.columns()).extracting(StructureResponse.Column::name).containsExactly("message");
        assertThat(logs.editable()).isFalse();
    }

    @Test
    @DisplayName("행 조회 — 값은 문자열·null·잘린 셀·이진 셀로 내려간다(숫자를 JSON 숫자로 내리지 않는다)")
    void rendersCellValues() {
        RowsResponse response = rows("users", null);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(100);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.rows()).hasSize(3);
        // 기본 정렬은 기본 키 오름차순
        assertThat(cell(response, 0, "id")).isEqualTo("1");
        assertThat(cell(response, 2, "id")).isEqualTo("3");
        // 2^53을 넘는 정수와 큰 십진수도 자릿수 그대로
        assertThat(cell(response, 0, "big")).isEqualTo("9007199254740993");
        assertThat(cell(response, 0, "balance")).isEqualTo("12345678901234567.89");
        // NULL과 빈 문자열은 다른 값이다
        assertThat(cell(response, 1, "name")).isNull();
        assertThat(cell(response, 1, "bio")).isEqualTo("");
        assertThat(cell(response, 0, "is_active")).isEqualTo("true");
        assertThat(cell(response, 1, "is_active")).isEqualTo("false");
        assertThat(cell(response, 0, "created_at")).isEqualTo("2026-09-30T05:10:00");
        // 2,000자를 넘는 문자 값은 잘린다
        assertThat(cell(response, 0, "bio")).isInstanceOfSatisfying(Map.class, truncated -> {
            assertThat(truncated.get("truncated")).isEqualTo(true);
            assertThat((String) truncated.get("text")).hasSize(2000);
            assertThat(truncated.get("length")).isEqualTo(3000);
        });
        // 이진 값은 길이와 앞 32바이트의 16진 표기
        assertThat(cell(response, 0, "avatar")).isInstanceOfSatisfying(Map.class, binary -> {
            assertThat(binary.get("binary")).isEqualTo(true);
            assertThat(binary.get("length")).isEqualTo(40);
            assertThat((String) binary.get("previewHex")).hasSize(64).startsWith("000102");
        });
        assertThat(cell(response, 1, "avatar")).isNull();
        // 컬럼 메타가 함께 내려간다
        ColumnMeta id = response.columns().get(indexOf(response, "id"));
        assertThat(id.primaryKey()).isTrue();
        assertThat(id.category()).isEqualTo("integer");
    }

    @Test
    @DisplayName("행 조회 — 페이지 넘김은 다음 페이지 유무만 알린다")
    void pages() {
        RowsResponse first = rows("orders", new RowsRequest(1, 2, null, null));
        assertThat(first.rows()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(cell(first, 0, "id")).isEqualTo("1");

        RowsResponse last = rows("orders", new RowsRequest(3, 2, null, null));
        assertThat(last.rows()).hasSize(1);
        assertThat(last.hasNext()).isFalse();
        assertThat(cell(last, 0, "id")).isEqualTo("5");
    }

    @Test
    @DisplayName("행 조회 — 조건(비교·IN·NULL·LIKE)과 정렬")
    void filtersAndSorts() {
        RowsResponse paid = rows("orders", new RowsRequest(null, null,
                List.of(new Filter("status", Op.EQ, "PAID")), List.of(new Sort("id", Direction.DESC))));
        assertThat(paid.rows()).extracting(row -> row.get(indexOf(paid, "id"))).containsExactly("4", "2", "1");

        RowsResponse recent = rows("orders", new RowsRequest(null, null,
                List.of(new Filter("status", Op.EQ, "PAID"), new Filter("ordered_at", Op.GTE, "2026-09-15")), null));
        assertThat(recent.rows()).extracting(row -> row.get(indexOf(recent, "id"))).containsExactly("2", "4");

        RowsResponse byNumber = rows("orders", new RowsRequest(null, null,
                List.of(new Filter("user_id", Op.EQ, "2")), null));
        assertThat(byNumber.rows()).hasSize(2);

        RowsResponse in = rows("orders", new RowsRequest(null, null,
                List.of(new Filter("status", Op.IN, List.of("READY", "CANCELLED"))), null));
        assertThat(in.rows()).extracting(row -> row.get(indexOf(in, "id"))).containsExactly("3", "5");

        RowsResponse noName = rows("users", new RowsRequest(null, null,
                List.of(new Filter("name", Op.IS_NULL, null)), null));
        assertThat(noName.rows()).singleElement().satisfies(row -> assertThat(row.get(indexOf(noName, "id"))).isEqualTo("2"));

        // LIKE의 %·_는 글자 그대로 찾는다 — 'c_d%e@…'만 걸리고 다른 주소는 걸리지 않는다
        RowsResponse literal = rows("users", new RowsRequest(null, null,
                List.of(new Filter("email", Op.CONTAINS, "_d%")), null));
        assertThat(literal.rows()).singleElement().satisfies(row -> assertThat(row.get(indexOf(literal, "id"))).isEqualTo("3"));

        RowsResponse prefix = rows("users", new RowsRequest(null, null,
                List.of(new Filter("email", Op.STARTS_WITH, "a@")), null));
        assertThat(prefix.rows()).hasSize(1);

        // 숫자 컬럼에도 부분 일치를 걸 수 있다(문자로 바꿔 비교)
        RowsResponse numericLike = rows("orders", new RowsRequest(null, null,
                List.of(new Filter("user_id", Op.CONTAINS, "1")), null));
        assertThat(numericLike.rows()).hasSize(3);
    }

    @Test
    @DisplayName("뷰도 조회된다 — 기본 키가 없어 정렬 없이 읽는다")
    void readsViews() {
        RowsResponse response = rows("paid_orders", null);

        assertThat(response.rows()).hasSize(3);
        assertThat(response.columns()).extracting(ColumnMeta::name).containsExactly("id", "user_id");
    }

    @Test
    @DisplayName("정확한 행 수 — 조건을 함께 받는다")
    void counts() {
        assertThat(service.count(USER, WORKSPACE, CONNECTION, "orders", null).count()).isEqualTo("5");
        assertThat(service.count(USER, WORKSPACE, CONNECTION, "orders",
                new CountRequest(List.of(new Filter("status", Op.EQ, "PAID")))).count()).isEqualTo("3");
    }

    @Test
    @DisplayName("요청 검증 — 없는 객체·없는 컬럼·한도 초과")
    void rejectsInvalidRequests() {
        assertError(() -> rows("nope", null), ErrorCode.OBJECT_NOT_FOUND);
        // 다른 스키마·따옴표가 섞인 이름은 카탈로그에 없으므로 그대로 거부된다(SQL에 붙지 않는다)
        assertError(() -> rows("users\"; DROP TABLE users; --", null), ErrorCode.OBJECT_NOT_FOUND);
        assertError(() -> rows("users", new RowsRequest(null, null,
                List.of(new Filter("nope", Op.EQ, "1")), null)), ErrorCode.INVALID_REQUEST);
        assertError(() -> rows("users", new RowsRequest(null, null, null,
                List.of(new Sort("id; DROP TABLE users", Direction.ASC)))), ErrorCode.INVALID_REQUEST);
        assertError(() -> rows("users", new RowsRequest(1, 501, null, null)), ErrorCode.INVALID_REQUEST);
        assertError(() -> rows("users", new RowsRequest(null, null,
                List.of(new Filter("id", Op.EQ, null)), null)), ErrorCode.INVALID_REQUEST);
        List<Filter> tooMany = java.util.Collections.nCopies(11, new Filter("id", Op.IS_NOT_NULL, null));
        assertError(() -> rows("users", new RowsRequest(null, null, tooMany, null)), ErrorCode.INVALID_REQUEST);
        // 테이블은 그대로 남아 있다
        assertThat(service.count(USER, WORKSPACE, CONNECTION, "users", null).count()).isEqualTo("3");
    }

    @Test
    @DisplayName("응답 크기 한도 — 넘으면 행을 줄이고 truncated로 알린다")
    void truncatesByResponseSize() {
        // 첫 행(잘린 bio 2,000자 포함)만으로 한도 2,100을 넘는다 — 첫 행은 항상 내보내고 둘째 행에서 자른다
        LimitsProperties tiny = new LimitsProperties(Duration.ofSeconds(5), Duration.ofSeconds(8), 100, 500, 500, 1000,
                2000, 1_000_000, 2_100, 10, 3, 100, 100_000, 2);
        RowsResponse response = serviceFor(tiny, access()).rows(USER, WORKSPACE, CONNECTION, "users", null);

        assertThat(response.truncated()).isTrue();
        assertThat(response.hasNext()).isTrue();
        assertThat(response.rows()).hasSize(1);
    }

    @Test
    @DisplayName("접속 실패 — 예외 원문 없이 분류된 CONNECTION_UNREACHABLE")
    void reportsUnreachable() {
        ConnectionAccess a = access();
        ConnectionAccess wrongPassword = new ConnectionAccess(a.connectionId(), a.connectionName(), a.workspaceId(),
                a.role(), a.dbmsType(), a.host(), a.port(), a.databaseName(), a.schemaName(), a.username(), "wrong-pw");

        assertThatThrownBy(() -> serviceFor(LIMITS, wrongPassword).objects(USER, WORKSPACE, CONNECTION))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_UNREACHABLE);
                    assertThat(ex.getMessageKey()).isEqualTo("jdbc.auth");
                });
        // 접속하지 못했으면 접근 감사도 남기지 않는다
        assertThat(audits).isEmpty();
    }

    @Test
    @DisplayName("읽기 전용 접속 — 쓰기 문장은 데이터베이스가 거부한다")
    void readOnlyConnectionRejectsWrites() throws SQLException {
        Connection connection = targetDatabase.openReadOnly(access());
        try (Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM orders"))
                    .isInstanceOf(SQLException.class);
        } finally {
            TargetDatabase.closeQuietly(connection);
        }
        assertThat(service.count(USER, WORKSPACE, CONNECTION, "orders", null).count()).isEqualTo("5");
    }

    static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    /** 3,000자 문자열 */
    static String longText() {
        return "가나다라마바사아자차".repeat(300);
    }

    /** 0x00~0x27 40바이트의 16진 표기 */
    static String blobHex() {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            hex.append(String.format("%02x", i));
        }
        return hex.toString();
    }
}
