package io.github.doantrantuandat.example.gateway.config;

import com.sun.net.httpserver.HttpServer;
import io.crnk.client.CrnkClient;
import io.crnk.core.queryspec.QuerySpec;
import io.github.doantrantuandat.example.gateway.remote.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers a review finding: ordering-service's OrderRepositoryDecorator.create() unconditionally requires
 * an X-Mock-Role: admin header (its own mock auth scheme - see that project's MockAuth.java), and nothing
 * else on orderingClient's own request path would ever set it. Confirms the OkHttp interceptor
 * BackendClientsConfig.orderingClient() wires in actually puts that header on an outgoing request, by
 * inspecting what a real (fake) server received - not just that the code compiles.
 */
class BackendClientsConfigTest {

    private HttpServer server;
    private final AtomicReference<String> receivedMockRoleHeader = new AtomicReference<>();

    @BeforeEach
    void startFakeBackend() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/order/1", exchange -> {
            receivedMockRoleHeader.set(exchange.getRequestHeaders().getFirst("X-Mock-Role"));
            String body = "{\"data\":{\"type\":\"order\",\"id\":\"1\",\"attributes\":{\"orderNumber\":\"ORD-1\"}}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeBackend() {
        server.stop(0);
    }

    @Test
    void orderingClientAttachesMockRoleAdminHeaderToEveryRequest() {
        CrnkClient client = new BackendClientsConfig()
                .orderingClient("http://localhost:" + server.getAddress().getPort());

        client.getRepositoryForType(Order.class).findOne(1L, new QuerySpec(Order.class));

        assertEquals("admin", receivedMockRoleHeader.get());
    }
}
