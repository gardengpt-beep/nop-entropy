package com.company.erp.runtime;

import com.company.erp.runtime.metadata.BizModelLoader;
import com.company.erp.runtime.metadata.ColumnDefinition;
import com.company.erp.runtime.metadata.EntityDefinition;
import com.company.erp.runtime.metadata.MetadataLoader;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Extremely small runtime that interprets the metadata files located under the
 * {@code _vfs} directory. It is not a full replacement of the real Nop runtime
 * but it mimics the parts that are required for the demo and test.
 */
public final class ModelDrivenErpEngine {

    private final EntityDefinition productDefinition;
    private final String activeStatusCode;
    private final Map<String, Map<String, Object>> products = new LinkedHashMap<>();

    public ModelDrivenErpEngine() {
        this.productDefinition = MetadataLoader.loadProductEntity();
        this.activeStatusCode = BizModelLoader.loadActiveStatusCode();
    }

    public String describeProductEntity() {
        StringBuilder builder = new StringBuilder(productDefinition.getName()).append("[");
        boolean first = true;
        for (ColumnDefinition column : productDefinition.getColumns()) {
            if (!first) {
                builder.append(',');
            }
            builder.append(column.getName());
            if (column.isMandatory()) {
                builder.append('*');
            }
            first = false;
        }
        return builder.append(']').toString();
    }

    public Map<String, Object> save(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload");
        Map<String, Object> normalized = normalizeForSave(payload);
        String productId = normalized.get("productId").toString();
        Map<String, Object> existing = products.get(productId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            normalized.put("createdTime", now);
        } else {
            normalized.put("createdTime", existing.get("createdTime"));
        }
        normalized.put("updatedTime", now);
        products.put(productId, normalized);
        return successResponse(copyOf(normalized));
    }

    public Map<String, Object> get(String productId) {
        Map<String, Object> data = products.get(productId);
        if (data == null) {
            throw new IllegalArgumentException("Unknown product " + productId);
        }
        return successResponse(copyOf(data));
    }

    public Map<String, Object> activeFindPage(int limit) {
        List<Map<String, Object>> matching = new ArrayList<>();
        for (Map<String, Object> value : products.values()) {
            if (activeStatusCode.equals(value.get("status"))) {
                matching.add(copyOf(value));
            }
        }
        int total = matching.size();
        int effectiveLimit = limit <= 0 ? total : Math.min(limit, total);
        List<Map<String, Object>> pageItems = matching.subList(0, effectiveLimit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("items", pageItems);
        return successResponse(data);
    }

    private Map<String, Object> normalizeForSave(Map<String, Object> payload) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (ColumnDefinition column : productDefinition.getColumns()) {
            String name = column.getName();
            Object value = payload.get(name);
            if (value == null) {
                if (column.isSequence()) {
                    value = UUID.randomUUID().toString();
                } else if (column.getDefaultValue() != null) {
                    value = column.getDefaultValue();
                } else if (column.isMandatory()) {
                    throw new IllegalArgumentException("Missing mandatory field " + name);
                }
            }
            if (value != null) {
                normalized.put(name, coerceValue(name, value));
            }
        }
        enforceUniqueConstraints(normalized);
        return normalized;
    }

    private Object coerceValue(String name, Object value) {
        if (value instanceof BigDecimal || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            if ("unitPrice".equals(name)) {
                return new BigDecimal(trimmed);
            }
            if ("stockQuantity".equals(name)) {
                return Integer.parseInt(trimmed);
            }
            return trimmed;
        }
        return value;
    }

    private void enforceUniqueConstraints(Map<String, Object> normalized) {
        for (ColumnDefinition column : productDefinition.getColumns()) {
            if (!column.isUnique()) {
                continue;
            }
            Object candidate = normalized.get(column.getName());
            if (candidate == null) {
                continue;
            }
            for (Map<String, Object> existing : products.values()) {
                if (Objects.equals(existing.get(column.getName()), candidate)) {
                    throw new IllegalArgumentException(
                            "Duplicate value for unique column " + column.getName());
                }
            }
        }
    }

    private Map<String, Object> successResponse(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 0);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> copyOf(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> mapValue) {
                copy.put(entry.getKey(), copyOf((Map<String, Object>) mapValue));
            } else if (value instanceof List<?> listValue) {
                List<Object> clone = new ArrayList<>(listValue.size());
                clone.addAll(listValue);
                copy.put(entry.getKey(), clone);
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    public Map<String, Object> exportAll() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("products", new ArrayList<>(products.values()));
        return data;
    }
}
