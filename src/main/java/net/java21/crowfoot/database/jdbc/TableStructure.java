package net.java21.crowfoot.database.jdbc;

import java.util.List;

/**
 * 테이블·뷰의 구조 (00-data-browser.md Section 3.2).
 */
public record TableStructure(
        String name,
        boolean view,
        String comment,
        List<Column> columns,
        List<String> primaryKey,
        List<Index> indexes,
        List<ForeignKey> foreignKeys
) {

    public record Column(String name, String typeName, String category, boolean nullable, boolean primaryKey,
                         String defaultValue, boolean autoIncrement, String comment, int jdbcType) {
    }

    public record Index(String name, boolean unique, List<String> columns) {
    }

    public record ForeignKey(String name, List<String> columns, String referencedObject, List<String> referencedColumns) {
    }

    /** 행 편집을 할 수 있는지 — 기본 키가 있는 테이블만(Section 3.1) */
    public boolean editable() {
        return !view && !primaryKey.isEmpty();
    }

    public Column column(String name) {
        return columns.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }
}
