package net.java21.crowfoot.database.browse.dto;

import net.java21.crowfoot.database.jdbc.TableStructure;

import java.util.List;

/**
 * 구조 보기 응답 (00-data-browser.md Section 3.2) — 읽기 전용.
 */
public record StructureResponse(
        String name,
        String kind,
        String comment,
        boolean editable,
        List<Column> columns,
        List<String> primaryKey,
        List<TableStructure.Index> indexes,
        List<TableStructure.ForeignKey> foreignKeys
) {

    public record Column(String name, String typeName, String category, boolean nullable, boolean primaryKey,
                         String defaultValue, boolean autoIncrement, String comment) {
    }

    public static StructureResponse of(TableStructure structure) {
        return new StructureResponse(
                structure.name(),
                structure.view() ? "VIEW" : "TABLE",
                structure.comment(),
                structure.editable(),
                structure.columns().stream()
                        .map(c -> new Column(c.name(), c.typeName(), c.category(), c.nullable(), c.primaryKey(),
                                c.defaultValue(), c.autoIncrement(), c.comment()))
                        .toList(),
                structure.primaryKey(),
                structure.indexes(),
                structure.foreignKeys());
    }
}
