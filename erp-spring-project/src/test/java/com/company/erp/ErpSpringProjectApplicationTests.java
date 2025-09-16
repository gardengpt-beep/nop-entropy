package com.company.erp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErpSpringProjectApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void metadataDrivenCrudFlowThroughRestEndpoints() {
        String sku = "SKU-" + System.currentTimeMillis();

        Map<String, Object> productData = new LinkedHashMap<>();
        productData.put("sku", sku);
        productData.put("name", "模型驱动演示商品");
        productData.put("category", "演示");
        productData.put("description", "SpringBootTest 验证流程");
        productData.put("unitPrice", new BigDecimal("49.90"));
        productData.put("stockQuantity", 12);
        productData.put("status", "ACTIVE");

        Map<String, Object> savePayload = new LinkedHashMap<>();
        savePayload.put("data", productData);

        JsonNode saveBody = postForJson(
                "/r/ErpProduct__save",
                Map.of("@selection", "productId,sku,status,unitPrice"),
                savePayload
        );

        assertEquals(0, saveBody.path("code").asInt(), "save code");
        JsonNode saved = saveBody.path("data");
        assertNotNull(saved);
        String productId = saved.path("productId").asText();
        assertFalse(productId.isBlank(), "productId should be generated");
        assertEquals(sku, saved.path("sku").asText(), "SKU after save");
        assertEquals("ACTIVE", saved.path("status").asText(), "status after save");
        assertEquals(0, new BigDecimal("49.90").compareTo(new BigDecimal(saved.path("unitPrice").asText())),
                "unitPrice after save");

        JsonNode getBody = getForJson(
                "/r/ErpProduct__get",
                Map.of(
                        "id", productId,
                        "@selection", "productId,sku,unitPrice,status"
                )
        );

        assertEquals(0, getBody.path("code").asInt(), "get code");
        JsonNode reloaded = getBody.path("data");
        assertEquals(productId, reloaded.path("productId").asText(), "productId after reload");
        assertEquals(sku, reloaded.path("sku").asText(), "SKU after reload");
        assertEquals("ACTIVE", reloaded.path("status").asText(), "status after reload");
        assertEquals(0, new BigDecimal("49.90").compareTo(new BigDecimal(reloaded.path("unitPrice").asText())),
                "unitPrice after reload");

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("limit", 10);

        Map<String, Object> pagePayload = new LinkedHashMap<>();
        pagePayload.put("query", query);

        JsonNode pageBody = postForJson(
                "/r/ErpProduct__active_findPage",
                Map.of("@selection", "total,items{productId,sku,status}"),
                pagePayload
        );

        assertEquals(0, pageBody.path("code").asInt(), "page code");
        JsonNode pageData = pageBody.path("data");
        assertTrue(pageData.path("total").asLong() >= 1, "total should be >= 1");
        boolean found = false;
        if (pageData.has("items") && pageData.get("items").isArray()) {
            for (JsonNode item : pageData.get("items")) {
                if (productId.equals(item.path("productId").asText())) {
                    found = true;
                    assertEquals(sku, item.path("sku").asText(), "SKU in page result");
                    assertEquals("ACTIVE", item.path("status").asText(), "status in page result");
                    break;
                }
            }
        }
        assertTrue(found, "page result should contain saved product");
    }

    private JsonNode postForJson(String path, Map<String, String> queryParams, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, ?>> request = new HttpEntity<>(body, headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                urlWithParams(path, queryParams), HttpMethod.POST, request, JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "HTTP status");
        JsonNode responseBody = response.getBody();
        assertNotNull(responseBody, "response body");
        return responseBody;
    }

    private JsonNode getForJson(String path, Map<String, String> queryParams) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                urlWithParams(path, queryParams), HttpMethod.GET, null, JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "HTTP status");
        JsonNode responseBody = response.getBody();
        assertNotNull(responseBody, "response body");
        return responseBody;
    }

    private String urlWithParams(String path, Map<String, String> params) {
        StringBuilder sb = new StringBuilder("http://localhost:")
                .append(port)
                .append(path);
        if (params != null && !params.isEmpty()) {
            sb.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    sb.append("&");
                }
                first = false;
                sb.append(entry.getKey())
                        .append("=")
                        .append(encode(entry.getValue()));
            }
        }
        return sb.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
