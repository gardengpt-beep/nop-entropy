package com.company.erp.runtime.metadata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates column definitions for a single entity.
 */
public final class EntityDefinition {

    private final String name;
    private final Map<String, ColumnDefinition> columns = new LinkedHashMap<>();

    public EntityDefinition(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String getName() {
        return name;
    }

    public void addColumn(ColumnDefinition column) {
        columns.put(column.getName(), column);
    }

    public ColumnDefinition getColumn(String columnName) {
        ColumnDefinition column = columns.get(columnName);
        if (column == null) {
            throw new IllegalArgumentException("Unknown column: " + columnName);
        }
        return column;
    }

    public Collection<ColumnDefinition> getColumns() {
        return columns.values();
    }
}
