package io.github.doantrantuandat.example.gateway;

import com.sun.net.httpserver.HttpServer;
import io.crnk.client.CrnkClient;
import io.crnk.client.http.okhttp.OkHttpAdapter;
import io.crnk.core.queryspec.QuerySpec;
import io.github.doantrantuandat.example.gateway.remote.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates this task's flagged risk directly, before anything else in this project was built on top of
 * it: every other crnk-client usage in this workspace runs in a process that also has a
 * crnk-setup-spring* module on its own classpath (which is what CrnkClient auto-detects its HTTP adapter
 * from). This gateway has no such sibling. This test proves CrnkClient, with its HTTP adapter forced to
 * OkHttp exactly as BackendClientsConfig does, can still make a real HTTP call over the network and
 * correctly deserialize a real JSON:API response, with no crnk server module anywhere on the classpath.
 *
 * Uses a plain JDK HttpServer rather than one of this workspace's own services as the "live backend":
 * the thing actually in question is crnk-client-jackson3's own internals (HTTP adapter selection,
 * Jackson 3 ObjectMapper setup, resource-information bootstrapping) in a process without a crnk server
 * module - not whether accounts-service's own code or its Postgres datasource works, which Task 1 already
 * proved. A hand-rolled JSON:API response isolates exactly the variable this task was told to worry
 * about, with no extra infrastructure (no Postgres, no port coordination with another process).
 *
 * Result: PASSED on the first run. CrnkClient + OkHttpAdapter.newInstance() works correctly with no
 * crnk-setup-spring* module on the classpath.
 */
class CrnkClientSmokeTest {

    private HttpServer server;
    private CrnkClient client;

    @BeforeEach
    void startFakeBackend() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/account/1", exchange -> {
            String body = "{\"data\":{\"type\":\"account\",\"id\":\"1\",\"attributes\":{"
                    + "\"name\":\"Alice\",\"email\":\"alice@example.com\",\"plan\":\"pro\"}}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        client = new CrnkClient("http://localhost:" + server.getAddress().getPort());
        client.setHttpAdapter(OkHttpAdapter.newInstance());
    }

    @AfterEach
    void stopFakeBackend() {
        server.stop(0);
    }

    @Test
    void crnkClientWithForcedOkHttpAdapterReadsRealResponseWithNoServerModuleOnClasspath() {
        Account account = client.getRepositoryForType(Account.class).findOne(1L, new QuerySpec(Account.class));
        assertEquals(1L, account.getId());
        assertEquals("Alice", account.getName());
        assertEquals("alice@example.com", account.getEmail());
        assertEquals("pro", account.getPlan());
    }
}
