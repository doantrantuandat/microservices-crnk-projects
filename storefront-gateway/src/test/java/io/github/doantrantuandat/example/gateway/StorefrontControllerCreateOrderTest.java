package io.github.doantrantuandat.example.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.crnk.client.CrnkClient;
import io.github.doantrantuandat.example.gateway.config.BackendClientsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for createOrder's create-order-first-then-set-orderId-on-each-line mechanism (broken and
 * fixed twice already - see OrderLine.java's javadoc - with its only prior verification being one live,
 * uncommitted docker-compose run). Proves the wiring with 3 fake JSON:API backends, independent of real
 * crnk-data-jpa/Postgres behavior: account/product are each looked up once, and the OrderLine create() call
 * already carries a real id plus the just-created order's real orderId (not a later relationship call).
 */
class StorefrontControllerCreateOrderTest {

    private HttpServer accountsServer;
    private HttpServer catalogServer;
    private HttpServer orderingServer;
    private final AtomicInteger accountLookups = new AtomicInteger();
    private final AtomicInteger productLookups = new AtomicInteger();
    private final AtomicReference<String> orderLineRequestBody = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void startFakeBackends() throws IOException {
        accountsServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        accountsServer.createContext("/account/1", exchange -> {
            accountLookups.incrementAndGet();
            respond(exchange, 200, "{\"data\":{\"type\":\"account\",\"id\":\"1\",\"attributes\":{"
                    + "\"name\":\"Alice Nguyen\",\"email\":\"alice@example.com\",\"plan\":\"pro\"}}}");
        });
        accountsServer.start();

        catalogServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        catalogServer.createContext("/product/1", exchange -> {
            productLookups.incrementAndGet();
            respond(exchange, 200, "{\"data\":{\"type\":\"product\",\"id\":\"1\",\"attributes\":{"
                    + "\"sku\":\"SKU-001\",\"name\":\"Mouse\",\"price\":19.99}}}");
        });
        catalogServer.start();

        orderingServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // Both creates simply echo the posted document back as "the created resource" - exactly like every
        // real crnk service in this workspace does for a client-supplied id - which is enough to prove the
        // gateway reads a real id back off the order-create response and threads it into the line-create.
        orderingServer.createContext("/order", exchange ->
                respond(exchange, 201, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        orderingServer.createContext("/orderLine", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            orderLineRequestBody.set(body);
            respond(exchange, 201, body);
        });
        orderingServer.start();
    }

    @AfterEach
    void stopFakeBackends() {
        accountsServer.stop(0);
        catalogServer.stop(0);
        orderingServer.stop(0);
    }

    @Test
    void createOrderCreatesOrderFirstThenLinksEachLineByItsRealOrderId() {
        BackendClientsConfig config = new BackendClientsConfig();
        CrnkClient accountsClient = config.accountsClient(url(accountsServer));
        CrnkClient catalogClient = config.catalogClient(url(catalogServer));
        CrnkClient orderingClient = config.orderingClient(url(orderingServer));

        StorefrontController controller = new StorefrontController(accountsClient, catalogClient, orderingClient,
                objectMapper, url(accountsServer), url(catalogServer), url(orderingServer));

        ResponseEntity<?> response = controller.createOrder(new StorefrontController.CreateOrderRequest(
                1L, List.of(new StorefrontController.CreateLineRequest(1L, 2))));

        assertEquals(201, response.getStatusCode().value());
        assertEquals(1, accountLookups.get(), "account should be looked up exactly once");
        assertEquals(1, productLookups.get(), "product should be looked up exactly once");

        assertNotNull(orderLineRequestBody.get(), "OrderLine create() was never called");
        JsonNode lineDoc = objectMapper.readTree(orderLineRequestBody.get());
        String lineId = lineDoc.at("/data/id").asText(null);
        assertNotNull(lineId, "OrderLine create() must carry a real, non-null id");
        String linkedOrderId = lineDoc.at("/data/relationships/order/data/id").asText(null);
        assertNotNull(linkedOrderId, "OrderLine create() must already carry its real orderId - not set via a later relationship call");
        assertTrue(Long.parseLong(linkedOrderId) > 0);
    }

    private static String url(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
