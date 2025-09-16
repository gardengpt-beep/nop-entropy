package com.company.erp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErpSpringProjectApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private String urlWithSelection(String path, String selection) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl("http://localhost:" + port + path);
        if (selection != null) {
            builder.queryParam("@selection", selection);
        }
        return builder.toUriString();
    }

    @Test
    void productCrudFlowIsDrivenByNopModels() {
        String sku = "SKU-" + System.currentTimeMillis();

        Map<String, Object> productData = new LinkedHashMap<>();
        productData.put("sku", sku);
        productData.put("name", "模型驱动演示商品");
        productData.put("category", "演示");
        productData.put("unitPrice", new BigDecimal("49.90"));
        productData.put("stockQuantity", 12);
        productData.put("status", "ACTIVE");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", productData);

        ResponseEntity<Map> saveResponse = restTemplate.postForEntity(
                urlWithSelection("/r/ErpProduct__save", "productId,sku,name,status"),
                payload,
                Map.class
        );

        assertThat(saveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> saveBody = saveResponse.getBody();
        assertThat(saveBody).isNotNull();
        assertThat(saveBody.get("status")).isEqualTo(0);
        Map<String, Object> saved = (Map<String, Object>) saveBody.get("data");
        assertThat(saved.get("sku")).isEqualTo(sku);
        String productId = (String) saved.get("productId");
        assertThat(productId).isNotBlank();

        String getUrl = UriComponentsBuilder
                .fromHttpUrl("http://localhost:" + port + "/r/ErpProduct__get")
                .queryParam("id", productId)
                .queryParam("@selection", "productId,sku,name,unitPrice,stockQuantity,status")
                .toUriString();
        ResponseEntity<Map> getResponse = restTemplate.getForEntity(getUrl, Map.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> getBody = getResponse.getBody();
        assertThat(getBody).isNotNull();
        assertThat(getBody.get("status")).isEqualTo(0);
        Map<String, Object> reloaded = (Map<String, Object>) getBody.get("data");
        assertThat(reloaded.get("sku")).isEqualTo(sku);
        assertThat(new BigDecimal(reloaded.get("unitPrice").toString()))
                .isEqualByComparingTo("49.90");

        Map<String, Object> queryPayload = Map.of("query", Map.of("limit", 10));
        ResponseEntity<Map> pageResponse = restTemplate.postForEntity(
                urlWithSelection("/r/ErpProduct__active_findPage", "total,items{productId,sku,status}"),
                queryPayload,
                Map.class
        );

        assertThat(pageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> pageBody = pageResponse.getBody();
        assertThat(pageBody).isNotNull();
        assertThat(pageBody.get("status")).isEqualTo(0);
        Map<String, Object> pageData = (Map<String, Object>) pageBody.get("data");
        assertThat(((Number) pageData.get("total")).longValue()).isGreaterThanOrEqualTo(1L);
        List<Map<String, Object>> items = (List<Map<String, Object>>) pageData.get("items");
        assertThat(items).anySatisfy(item -> assertThat(item.get("productId")).isEqualTo(productId));
    }
}
