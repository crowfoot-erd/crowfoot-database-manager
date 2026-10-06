package net.java21.crowfoot.database.jdbc;

/**
 * 결과 컬럼 메타 (00-data-browser.md Section 2.2) — 모든 결과에 함께 내려간다.
 *
 * @param name       컬럼 이름
 * @param typeName   표시용 타입 이름 — 길이·정밀도를 붙인 형태({@code VARCHAR(191)}·{@code DECIMAL(12,2)})
 * @param category   타입 분류 — integer·decimal·float·character·text·boolean·datetime·json·uuid·binary·other
 * @param nullable   NULL 허용
 * @param primaryKey 기본 키 컬럼
 */
public record ColumnMeta(String name, String typeName, String category, boolean nullable, boolean primaryKey,
                         boolean generated) {

    /** 질의 결과 열 — 생성 컬럼인지 알 수 없다 */
    public ColumnMeta(String name, String typeName, String category, boolean nullable, boolean primaryKey) {
        this(name, typeName, category, nullable, primaryKey, false);
    }
}
