package io.github.doantrantuandat.example.ordering;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// accounts.service.url / catalog.service.url point at addresses nothing listens on (port 1 is reserved) so
// AccountLinkerModule/ProductLinkerModule construct normally but any remote lookup would fail - not
// exercised by these tests (no ?include=account/product here, that's the integration task's job against
// the full docker-composed stack), matching this task's definition of done (no live accounts-service/
// catalog-service needed). The local Order.lines association IS exercised here (real JPA, same service).
//
// Uses the JDK's own java.net.http.HttpClient rather than Spring's TestRestTemplate: Spring Boot 4 removed
// TestRestTemplate entirely (confirmed empty across every spring-boot-test-4.1.1.jar class listing) in favor
// of a new spring-test 7 RestTestClient fluent API. Rather than take on a brand-new API's exact
// body/content-type semantics for what's otherwise a two-line HTTP call, the stable JDK HttpClient does the
// same job with zero additional risk.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"accounts.service.url=http://localhost:1", "catalog.service.url=http://localhost:1"})
class OrderingApplicationTest {

    private static final String JSONAPI = "application/vnd.api+json";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void adminCanCreateOrderWithLine() throws IOException, InterruptedException {
        String orderBody = "{\"data\":{\"type\":\"order\",\"id\":\"201\",\"attributes\":{"
                + "\"orderNumber\":\"ORD-201\"},"
                + "\"relationships\":{\"account\":{\"data\":{\"type\":\"account\",\"id\":\"1\"}}}}}";
        HttpResponse<String> orderResponse = post("/order", orderBody, "admin");
        assertEquals(201, orderResponse.statusCode());

        // OrderLine creation is deliberately NOT gated by MockAuth - no X-Mock-Role header sent at all.
        String lineBody = "{\"data\":{\"type\":\"orderLine\",\"id\":\"201\",\"attributes\":{"
                + "\"qty\":2},"
                + "\"relationships\":{\"order\":{\"data\":{\"type\":\"order\",\"id\":\"201\"}},"
                + "\"product\":{\"data\":{\"type\":\"product\",\"id\":\"1\"}}}}}";
        HttpResponse<String> lineResponse = post("/orderLine", lineBody, null);
        assertEquals(201, lineResponse.statusCode());
    }

    @Test
    void viewerCannotCreateOrder() throws IOException, InterruptedException {
        String body = "{\"data\":{\"type\":\"order\",\"id\":\"202\",\"attributes\":{"
                + "\"orderNumber\":\"ORD-202\"},"
                + "\"relationships\":{\"account\":{\"data\":{\"type\":\"account\",\"id\":\"1\"}}}}}";
        HttpResponse<String> response = post("/order", body, "viewer");
        assertEquals(403, response.statusCode());
    }

    @Test
    void blankOrderNumberFailsValidation() throws IOException, InterruptedException {
        String body = "{\"data\":{\"type\":\"order\",\"id\":\"203\",\"attributes\":{"
                + "\"orderNumber\":\"\"},"
                + "\"relationships\":{\"account\":{\"data\":{\"type\":\"account\",\"id\":\"1\"}}}}}";
        HttpResponse<String> response = post("/order", body, "admin");
        assertEquals(422, response.statusCode());
    }

    @Test
    void nonPositiveQtyFailsValidation() throws IOException, InterruptedException {
        String orderBody = "{\"data\":{\"type\":\"order\",\"id\":\"204\",\"attributes\":{"
                + "\"orderNumber\":\"ORD-204\"},"
                + "\"relationships\":{\"account\":{\"data\":{\"type\":\"account\",\"id\":\"1\"}}}}}";
        HttpResponse<String> orderResponse = post("/order", orderBody, "admin");
        assertEquals(201, orderResponse.statusCode());

        String lineBody = "{\"data\":{\"type\":\"orderLine\",\"id\":\"204\",\"attributes\":{"
                + "\"qty\":0},"
                + "\"relationships\":{\"order\":{\"data\":{\"type\":\"order\",\"id\":\"204\"}},"
                + "\"product\":{\"data\":{\"type\":\"product\",\"id\":\"1\"}}}}}";
        HttpResponse<String> lineResponse = post("/orderLine", lineBody, null);
        assertEquals(422, lineResponse.statusCode());
    }

    @Test
    void getOrderByIdReturnsExpectedFieldsIncludingLines() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/order/1?include=lines");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"type\":\"order\""));
        assertTrue(body.contains("\"id\":\"1\""));
        assertTrue(body.contains("ORD-1001"));
        // Local lines association resolved via real JPA (no remote call needed): the seeded line (id 1)
        // shows up in the included section.
        assertTrue(body.contains("\"type\":\"orderLine\""));
    }

    private HttpResponse<String> post(String path, String body, String mockRole) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", JSONAPI)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (mockRole != null) {
            builder.header("X-Mock-Role", mockRole);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
