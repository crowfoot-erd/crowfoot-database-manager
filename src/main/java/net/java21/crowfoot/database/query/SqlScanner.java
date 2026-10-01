package net.java21.crowfoot.database.query;

import java.util.Locale;

/**
 * SQL 한 덩어리를 훑어 첫 키워드와 문장 수를 센다 (00-data-browser.md Section 3.6).
 *
 * <p>따옴표·주석·PostgreSQL 달러 인용 안의 세미콜론은 문장 경계가 아니다. 문법을 해석하지 않는다 —
 * 경계와 첫 키워드만 본다. 분류는 확인 절차의 기준이지 안전 장치가 아니다: 읽기로 분류된 문장은
 * 읽기 전용 트랜잭션에서 실행되므로, 분류가 틀려도 데이터베이스가 쓰기를 거부한다.
 */
public final class SqlScanner {

    /**
     * @param firstKeyword   주석·공백·여는 괄호를 건너뛴 첫 키워드(대문자). 없으면 빈 문자열
     * @param statementCount 내용이 있는 문장의 수 — 끝의 세미콜론 하나는 문장을 늘리지 않는다
     */
    public record Scan(String firstKeyword, int statementCount) {
    }

    private SqlScanner() {
    }

    /**
     * @param mysql MySQL 방언이면 true — 백슬래시 이스케이프, {@code #} 주석, 큰따옴표 문자열, 백틱 식별자.
     *              false면 PostgreSQL — 달러 인용, 중첩 블록 주석, {@code E'…'}에서만 백슬래시 이스케이프
     */
    public static Scan scan(String sql, boolean mysql) {
        StringBuilder keyword = new StringBuilder();
        boolean keywordDone = false;
        boolean inStatement = false;      // 지금 문장에 내용(주석·공백이 아닌 것)이 있었는지
        int statements = 0;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            // 주석
            if (c == '-' && next == '-') {
                i = skipLine(sql, i);
                continue;
            }
            if (mysql && c == '#') {
                i = skipLine(sql, i);
                continue;
            }
            if (c == '/' && next == '*') {
                i = skipBlockComment(sql, i, !mysql);
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (keyword.length() > 0) {
                    keywordDone = true;
                }
                i++;
                continue;
            }
            if (c == ';') {
                if (inStatement) {
                    statements++;
                }
                inStatement = false;
                if (keyword.length() > 0) {
                    keywordDone = true;
                }
                i++;
                continue;
            }

            inStatement = true;
            // 인용 — 안의 내용은 통째로 건너뛴다
            if (c == '\'') {
                boolean backslash = mysql || (i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e'));
                i = skipQuoted(sql, i, '\'', backslash);
                keywordDone = keywordDone || keyword.length() > 0;
                continue;
            }
            if (c == '"') {
                i = skipQuoted(sql, i, '"', mysql);
                keywordDone = keywordDone || keyword.length() > 0;
                continue;
            }
            if (mysql && c == '`') {
                i = skipQuoted(sql, i, '`', false);
                keywordDone = keywordDone || keyword.length() > 0;
                continue;
            }
            if (!mysql && c == '$') {
                int end = dollarTagEnd(sql, i);
                if (end > 0) {
                    String tag = sql.substring(i, end + 1);
                    int close = sql.indexOf(tag, end + 1);
                    i = close < 0 ? n : close + tag.length();
                    keywordDone = keywordDone || keyword.length() > 0;
                    continue;
                }
            }
            // 첫 키워드 — 여는 괄호는 건너뛴다: (SELECT …) UNION …
            if (!keywordDone) {
                if (Character.isLetter(c) || (keyword.length() > 0 && (Character.isLetterOrDigit(c) || c == '_'))) {
                    keyword.append(c);
                } else if (c == '(' && keyword.length() == 0) {
                    // 계속 찾는다
                } else {
                    keywordDone = true;
                }
            }
            i++;
        }
        if (inStatement) {
            statements++;
        }
        return new Scan(keyword.toString().toUpperCase(Locale.ROOT), statements);
    }

    private static int skipLine(String sql, int from) {
        int end = sql.indexOf('\n', from);
        return end < 0 ? sql.length() : end + 1;
    }

    private static int skipBlockComment(String sql, int from, boolean nested) {
        int depth = 1;
        int i = from + 2;
        int n = sql.length();
        while (i < n && depth > 0) {
            if (sql.startsWith("*/", i)) {
                depth--;
                i += 2;
            } else if (nested && sql.startsWith("/*", i)) {
                depth++;
                i += 2;
            } else {
                i++;
            }
        }
        return i;
    }

    /** 여는 인용 문자 위치에서 시작해 닫는 인용 문자 다음 위치를 돌려준다. 같은 문자를 두 번 쓰면 이스케이프다 */
    private static int skipQuoted(String sql, int from, char quote, boolean backslashEscapes) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /** {@code $tag$}의 닫는 {@code $} 위치 — 달러 인용의 시작이 아니면 -1({@code $1} 같은 파라미터 표기) */
    private static int dollarTagEnd(String sql, int from) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '$') {
                return i;
            }
            if (!(Character.isLetter(c) || c == '_' || (i > from + 1 && Character.isDigit(c)))) {
                return -1;
            }
            i++;
        }
        return -1;
    }
}
