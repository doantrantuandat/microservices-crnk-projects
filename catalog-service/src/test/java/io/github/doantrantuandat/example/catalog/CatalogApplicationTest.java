package io.github.doantrantuandat.example.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// accounts.service.url points at an address nothing listens on (port 1 is reserved) so
// AccountLinkerModule constructs normally but any remote lookup would fail - not exercised by these
// tests (no ?include=owner here, that's the integration task's job against the full docker-composed
// stack), matching this task's definition of done (no live accounts-service needed).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "accounts.service.url=http://localhost:1")
class CatalogApplicationTest {

    private static final String JSONAPI = "application/vnd.api+json";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void adminCanCreateProduct() {
        String body = "{\"data\":{\"type\":\"product\",\"id\":\"101\",\"attributes\":{"
                + "\"sku\":\"SKU-101\",\"name\":\"New Product\",\"price\":9.99}}}";
        ResponseEntity<String> response = post(body, "admin");
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    void viewerCannotCreateProduct() {
        String body = "{\"data\":{\"type\":\"product\",\"id\":\"102\",\"attributes\":{"
                + "\"sku\":\"SKU-102\",\"name\":\"Blocked Product\",\"price\":9.99}}}";
        ResponseEntity<String> response = post(body, "viewer");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void blankSkuFailsValidation() {
        String body = "{\"data\":{\"type\":\"product\",\"id\":\"103\",\"attributes\":{"
                + "\"sku\":\"\",\"name\":\"Has A Name\",\"price\":9.99}}}";
        ResponseEntity<String> response = post(body, "admin");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    }

    @Test
    void blankNameFailsValidation() {
        String body = "{\"data\":{\"type\":\"product\",\"id\":\"104\",\"attributes\":{"
                + "\"sku\":\"SKU-104\",\"name\":\"\",\"price\":9.99}}}";
        ResponseEntity<String> response = post(body, "admin");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    }

    @Test
    void nonPositivePriceFailsValidation() {
        String body = "{\"data\":{\"type\":\"product\",\"id\":\"105\",\"attributes\":{"
                + "\"sku\":\"SKU-105\",\"name\":\"Free Product\",\"price\":0}}}";
        ResponseEntity<String> response = post(body, "admin");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    }

    @Test
    void getProductByIdReturnsExpectedFields() {
        ResponseEntity<String> response = restTemplate.getForEntity("/product/1", String.class);
        assertEquals(200, response.getStatusCode().value());
        String responseBody = response.getBody();
        assertTrue(responseBody.contains("\"type\":\"product\""));
        assertTrue(responseBody.contains("\"id\":\"1\""));
        assertTrue(responseBody.contains("SKU-001"));
    }

    private ResponseEntity<String> post(String body, String mockRole) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", JSONAPI);
        headers.set("X-Mock-Role", mockRole);
        return restTemplate.postForEntity("/product", new HttpEntity<>(body, headers), String.class);
    }
}
