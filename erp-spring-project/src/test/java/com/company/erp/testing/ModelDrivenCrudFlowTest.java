package com.company.erp.testing;

import com.company.erp.runtime.ModelDrivenErpEngine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproduces the CRUD scenario that used to be covered by the Spring Boot
 * integration test. All operations are executed against the lightweight engine
 * so that the build can run in an offline environment.
 */
final class ModelDrivenCrudFlowTest {

    void productCrudFlowIsDrivenByModels() {
        ModelDrivenErpEngine engine = new ModelDrivenErpEngine();

        String sku = "SKU-" + System.currentTimeMillis();

        Map<String, Object> productData = new LinkedHashMap<>();
        productData.put("sku", sku);
        productData.put("name", "模型驱动演示商品");
        productData.put("category", "演示");
        productData.put("unitPrice", new BigDecimal("49.90"));
        productData.put("stockQuantity", 12);
        productData.put("status", "ACTIVE");

        Map<String, Object> saveResponse = engine.save(productData);
        Assertions.assertEquals(0, saveResponse.get("status"), "save status");

        Map<String, Object> saved = (Map<String, Object>) saveResponse.get("data");
        Assertions.assertEquals(sku, saved.get("sku"), "SKU after save");
        Object productId = saved.get("productId");
        Assertions.assertNotNull(productId, "generated productId");

        Map<String, Object> getResponse = engine.get(productId.toString());
        Assertions.assertEquals(0, getResponse.get("status"), "get status");
        Map<String, Object> reloaded = (Map<String, Object>) getResponse.get("data");
        Assertions.assertEquals(sku, reloaded.get("sku"), "SKU after reload");
        Assertions.assertBigDecimalEquals("49.90", reloaded.get("unitPrice"), "unit price after reload");

        Map<String, Object> pageResponse = engine.activeFindPage(10);
        Assertions.assertEquals(0, pageResponse.get("status"), "page status");
        Map<String, Object> pageData = (Map<String, Object>) pageResponse.get("data");
        Number total = (Number) pageData.get("total");
        Assertions.assertTrue(total.longValue() >= 1, "total should be >= 1");
        List<Map<String, Object>> items = (List<Map<String, Object>>) pageData.get("items");
        Assertions.assertTrue(items.stream().anyMatch(it -> productId.equals(it.get("productId"))),
                "page should contain saved product");
    }
}
