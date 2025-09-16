package com.company.erp.runtime.metadata;

import java.util.Objects;

/**
 * Minimal column definition extracted from the ORM metadata. Only captures the
 * attributes that are required by the in-memory engine.
 */
public final class ColumnDefinition {

    private final String name;
    private final boolean mandatory;
    private final boolean unique;
    private final String defaultValue;
    private final boolean sequence;

    public ColumnDefinition(String name, boolean mandatory, boolean unique,
                            String defaultValue, boolean sequence) {
        this.name = Objects.requireNonNull(name, "name");
        this.mandatory = mandatory;
        this.unique = unique;
        this.defaultValue = defaultValue;
        this.sequence = sequence;
    }

    public String getName() {
        return name;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public boolean isUnique() {
        return unique;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public boolean isSequence() {
        return sequence;
    }
}
