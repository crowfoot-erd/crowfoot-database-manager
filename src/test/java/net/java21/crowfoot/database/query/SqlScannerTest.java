package net.java21.crowfoot.database.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 훑기 — 첫 키워드와 문장 수 (00-data-browser.md Section 3.6).
 */
class SqlScannerTest {

    private static final boolean MYSQL = true;
    private static final boolean POSTGRES = false;

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "select 1 | SELECT",
            "/* 블록 */ select 1 | SELECT",
            "(SELECT 1) UNION (SELECT 2) | SELECT",
            "((select 1)) | SELECT",
            "with t as (select 1) select * from t | WITH",
            "UPDATE users SET name = 'x' | UPDATE",
            "drop table users | DROP",
            "begin | BEGIN",
            "explain select 1 | EXPLAIN",
    })
    void findsFirstKeyword(String sql, String keyword) {
        assertThat(SqlScanner.scan(sql, MYSQL).firstKeyword()).isEqualTo(keyword);
        assertThat(SqlScanner.scan(sql, POSTGRES).firstKeyword()).isEqualTo(keyword);
    }

    @Test
    @DisplayName("앞의 공백·줄바꿈·줄 주석을 건너뛴다")
    void skipsLeadingWhitespaceAndLineComments() {
        assertThat(SqlScanner.scan("  \n\t SELECT 1", MYSQL).firstKeyword()).isEqualTo("SELECT");
        assertThat(SqlScanner.scan("-- 주석\nSELECT 1", POSTGRES).firstKeyword()).isEqualTo("SELECT");
    }

    @Test
    @DisplayName("주석 뒤에 숨긴 키워드도 찾는다 — 주석 안의 글자는 키워드가 아니다")
    void ignoresKeywordsInsideComments() {
        assertThat(SqlScanner.scan("/* SELECT */ DELETE FROM users", POSTGRES).firstKeyword()).isEqualTo("DELETE");
        assertThat(SqlScanner.scan("-- SELECT\nDROP TABLE users", MYSQL).firstKeyword()).isEqualTo("DROP");
        assertThat(SqlScanner.scan("# SELECT\nDROP TABLE users", MYSQL).firstKeyword()).isEqualTo("DROP");
    }

    @Test
    @DisplayName("끝의 세미콜론 하나는 문장을 늘리지 않는다 — 빈 문장도 세지 않는다")
    void countsStatements() {
        assertThat(SqlScanner.scan("SELECT 1", MYSQL).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT 1;", MYSQL).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT 1;  \n -- 끝\n", MYSQL).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT 1;;", MYSQL).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT 1; SELECT 2", MYSQL).statementCount()).isEqualTo(2);
        assertThat(SqlScanner.scan("SELECT 1; DROP TABLE users;", POSTGRES).statementCount()).isEqualTo(2);
        assertThat(SqlScanner.scan("  \n -- 주석만\n", MYSQL).statementCount()).isZero();
        assertThat(SqlScanner.scan("", POSTGRES).statementCount()).isZero();
    }

    @Test
    @DisplayName("따옴표·주석 안의 세미콜론은 문장 경계가 아니다")
    void ignoresSemicolonsInsideQuotesAndComments() {
        assertThat(SqlScanner.scan("SELECT 'a;b', \"c;d\" FROM t", MYSQL).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT 'it''s; fine'", POSTGRES).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT `a;b` FROM t", MYSQL).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT 1 /* ; */ + 2 -- ; \n", POSTGRES).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT 1 /* a /* 중첩; */ b; */ + 2", POSTGRES).statementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("따옴표로 숨긴 두 번째 문장을 놓치지 않는다 — 방언별 이스케이프 규칙")
    void doesNotMissSecondStatementBehindEscapes() {
        // MySQL — 백슬래시가 따옴표를 이스케이프한다: 문자열은 '\'; DROP…' 전체다 → 한 문장
        assertThat(SqlScanner.scan("SELECT '\\'; DROP TABLE users; --'", MYSQL).statementCount()).isEqualTo(1);
        // PostgreSQL — 백슬래시는 글자다: 문자열은 '\'에서 끝나고 그 뒤가 두 번째 문장이다
        assertThat(SqlScanner.scan("SELECT '\\'; DROP TABLE users", POSTGRES).statementCount()).isEqualTo(2);
        // PostgreSQL E'…' — 백슬래시 이스케이프가 켜진다
        assertThat(SqlScanner.scan("SELECT E'\\'; still string'", POSTGRES).statementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PostgreSQL 달러 인용 안의 세미콜론은 문장 경계가 아니다 — $1 같은 파라미터 표기는 인용이 아니다")
    void handlesDollarQuoting() {
        assertThat(SqlScanner.scan("SELECT $$a; b$$", POSTGRES).statementCount()).isEqualTo(1);
        assertThat(SqlScanner.scan("SELECT $tag$ x; $$ y; $tag$; SELECT 2", POSTGRES).statementCount()).isEqualTo(2);
        assertThat(SqlScanner.scan("SELECT $1; SELECT $2", POSTGRES).statementCount()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "SELECT,READ", "SHOW,READ", "EXPLAIN,READ", "DESCRIBE,READ", "DESC,READ", "VALUES,READ", "TABLE,READ", "WITH,READ",
            "INSERT,WRITE", "UPDATE,WRITE", "DELETE,WRITE", "REPLACE,WRITE", "MERGE,WRITE",
            "CREATE,DDL", "ALTER,DDL", "DROP,DDL", "TRUNCATE,DDL", "RENAME,DDL", "COMMENT,DDL", "GRANT,DDL", "REVOKE,DDL",
            "BEGIN,UNSUPPORTED", "START,UNSUPPORTED", "COMMIT,UNSUPPORTED", "ROLLBACK,UNSUPPORTED", "SAVEPOINT,UNSUPPORTED",
            "SET,UNSUPPORTED", "USE,UNSUPPORTED", "LOCK,UNSUPPORTED", "CALL,UNSUPPORTED", "VACUUM,UNSUPPORTED", "'',UNSUPPORTED",
    })
    void classifiesByFirstKeyword(String keyword, StatementKind kind) {
        assertThat(StatementKind.of(keyword)).isEqualTo(kind);
    }
}
