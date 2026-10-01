package net.java21.crowfoot.database.query;

import java.util.Set;

/**
 * 콘솔 문장의 종류 (00-data-browser.md Section 3.6) — 첫 키워드로 나눈다.
 */
public enum StatementKind {

    /** 읽기 전용 트랜잭션에서 확인 없이 실행한다 */
    READ,
    /** 자동 커밋. 사용자 확인이 있어야 실행한다 */
    WRITE,
    /** 자동 커밋. 사용자 확인이 있어야 실행한다 */
    DDL,
    /** 실행하지 않는다 — 트랜잭션 제어·세션 설정·프로시저 호출·그 밖 */
    UNSUPPORTED;

    private static final Set<String> READ_KEYWORDS = Set.of(
            "SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE", "WITH");
    private static final Set<String> WRITE_KEYWORDS = Set.of("INSERT", "UPDATE", "DELETE", "REPLACE", "MERGE");
    private static final Set<String> DDL_KEYWORDS = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "COMMENT", "GRANT", "REVOKE");

    public static StatementKind of(String firstKeyword) {
        if (READ_KEYWORDS.contains(firstKeyword)) {
            return READ;
        }
        if (WRITE_KEYWORDS.contains(firstKeyword)) {
            return WRITE;
        }
        if (DDL_KEYWORDS.contains(firstKeyword)) {
            return DDL;
        }
        return UNSUPPORTED;
    }

    public boolean requiresConfirmation() {
        return this == WRITE || this == DDL;
    }
}
