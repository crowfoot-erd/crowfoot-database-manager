package net.java21.crowfoot.database.jdbc;

import net.java21.crowfoot.database.client.dto.ConnectionAccess;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * DBMS별 차이 — 접속 URL·세션 설정·식별자 인용·카탈로그 조회 (09-database-manager/00-data-browser.md Section 2.4·3.1).
 *
 * <p>식별자는 SQL에 그대로 붙이지 않는다. 카탈로그에서 읽은 이름만 {@link #quote(String)}로 인용해 쓴다.
 */
public interface Dialect {

    /** database_types 코드 — {@code mysql}·{@code postgresql} */
    String dbmsType();

    String jdbcUrl(ConnectionAccess access);

    /** 드라이버 접속 속성 — 접속·소켓 제한 시간 등 */
    void applyDriverProperties(Properties props, Duration connectTimeout, Duration socketTimeout);

    /** 접속 직후 세션 설정 — PostgreSQL은 지정 스키마로 search_path를 고정한다(06-connection.md Section 3.2와 같은 방식) */
    void prepareSession(Connection connection, ConnectionAccess access) throws SQLException;

    /** 카탈로그 조회에 쓸 대상 스키마 이름 — MySQL은 database, PostgreSQL은 지정 스키마 또는 현재 스키마 */
    String resolveSchema(Connection connection, ConnectionAccess access) throws SQLException;

    /** 식별자 인용 — MySQL 백틱, PostgreSQL 큰따옴표. 인용 문자는 두 번 써서 이스케이프한다 */
    String quote(String identifier);

    /** {@code 스키마.객체}의 인용 표기 */
    default String qualified(String schema, String object) {
        return quote(schema) + "." + quote(object);
    }

    /** 문자열 비교(LIKE)를 위해 식을 문자 타입으로 바꾼다 */
    String castToText(String expression);

    /** 테이블·뷰 목록 — 이름순. 추정 행 수와 코멘트를 함께 읽는다(Section 3.1) */
    List<CatalogObject> listObjects(Connection connection, String schema) throws SQLException;

    /** DatabaseMetaData 호출에 넘길 catalog 인자 — MySQL은 database 이름, PostgreSQL은 null */
    String metadataCatalog(String schema);

    /** DatabaseMetaData 호출에 넘길 schema 인자 — MySQL은 null, PostgreSQL은 스키마 이름 */
    String metadataSchema(String schema);

    /**
     * 카탈로그의 객체 한 건.
     *
     * @param name          실제 이름(카탈로그 표기 그대로)
     * @param view          뷰면 true
     * @param estimatedRows 카탈로그 통계의 추정 행 수 — 통계가 없으면 null
     * @param comment       코멘트 — 없으면 null
     * @param hasPrimaryKey 기본 키가 있는 테이블인지 — 행 편집 가능 여부의 근거
     */
    record CatalogObject(String name, boolean view, Long estimatedRows, String comment, boolean hasPrimaryKey) {
    }
}
