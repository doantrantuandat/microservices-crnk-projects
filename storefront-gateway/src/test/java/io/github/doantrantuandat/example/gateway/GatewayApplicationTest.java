package io.github.doantrantuandat.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other 3 services aren't live during this task's own test run (see CrnkClientSmokeTest for the
 * separate, isolated proof that CrnkClient + a forced OkHttp adapter actually works against a real
 * backend). All 3 backend URL properties point at port 1 (reserved, nothing ever listens there -
 * connection refused immediately, no risk of hanging on an actually-unused-but-reachable port).
 *
 * Uses the JDK's own java.net.http.HttpClient rather than Spring's TestRestTemplate: Spring Boot 4 removed
 * TestRestTemplate entirely (confirmed in Task 3 by decompiling the actual spring-boot-test jar) in favor
 * of spring-test 7's new RestTestClient fluent API. Rather than take on a brand-new API's exact semantics
 * for what's otherwise a two-line HTTP call, the stable JDK HttpClient does the same job with zero
 * additional risk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "accounts.service.url=http://localhost:1",
                "catalog.service.url=http://localhost:1",
                "ordering.service.url=http://localhost:1"
        })
class GatewayApplicationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void healthReportsAllThreeBackendsUnreachableWithoutHangingOrThrowing() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/api/health");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"accounts\":\"UNREACHABLE\""), body);
        assertTrue(body.contains("\"catalog\":\"UNREACHABLE\""), body);
        assertTrue(body.contains("\"ordering\":\"UNREACHABLE\""), body);
    }

    @Test
    void createOrderWithoutAuthHeaderIsRejected() throws IOException, InterruptedException {
        String body = "{\"accountId\":1,\"lines\":[{\"productId\":1,\"qty\":2}]}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void rawAccountAgainstUnreachableBackendIsACleanResponseNotAStackTraceOrHang() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/api/raw/accounts/1");
        // Whatever status is chosen, it must be a clean, bounded response - not a 500 with a stack trace.
        assertFalse(response.body().contains("Exception"), response.body());
        assertFalse(response.body().contains("\tat "), response.body());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
