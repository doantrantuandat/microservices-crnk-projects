package io.github.doantrantuandat.example.gateway;

import io.crnk.client.CrnkClient;
import io.crnk.core.exception.ResourceNotFoundException;
import io.crnk.core.queryspec.QuerySpec;
import io.github.doantrantuandat.example.gateway.remote.Account;
import io.github.doantrantuandat.example.gateway.remote.Order;
import io.github.doantrantuandat.example.gateway.remote.OrderLine;
import io.github.doantrantuandat.example.gateway.remote.Product;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Plain Spring MVC controller - no crnk server dependency anywhere in this class or this project.
 * Talks to the 3 backend crnk servers purely as an external consumer, via the CrnkClient beans from
 * BackendClientsConfig (endpoints a/b) and Spring's own RestClient (endpoints c/d, see each method's
 * javadoc for why).
 */
@RestController
@RequestMapping("/api")
public class StorefrontController {

    private final CrnkClient accountsClient;
    private final CrnkClient catalogClient;
    private final CrnkClient orderingClient;
    private final ObjectMapper objectMapper;
    private final String accountsServiceUrl;
    private final String catalogServiceUrl;
    private final String orderingServiceUrl;

    // (c) has no stated timeout requirement in the brief - the JDK-default-backed RestClient is fine.
    private final RestClient rawClient = RestClient.create();

    // (d) must never hang on a stopped backend: a short, explicit connect/read timeout is the whole
    // point of this endpoint (graceful-degradation verification relies on it returning promptly).
    private final RestClient healthClient;

    // Backs withDeadline()'s wall-clock bound on (c) and (d) - virtual threads are cheap enough to spin
    // up per call, no pooling/sizing concerns to manage.
    private final ExecutorService boundedCallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public StorefrontController(@Qualifier("accountsClient") CrnkClient accountsClient,
                                 @Qualifier("catalogClient") CrnkClient catalogClient,
                                 @Qualifier("orderingClient") CrnkClient orderingClient,
                                 ObjectMapper objectMapper,
                                 @Value("${accounts.service.url}") String accountsServiceUrl,
                                 @Value("${catalog.service.url}") String catalogServiceUrl,
                                 @Value("${ordering.service.url}") String orderingServiceUrl) {
        this.accountsClient = accountsClient;
        this.catalogClient = catalogClient;
        this.orderingClient = orderingClient;
        this.objectMapper = objectMapper;
        this.accountsServiceUrl = accountsServiceUrl;
        this.catalogServiceUrl = catalogServiceUrl;
        this.orderingServiceUrl = orderingServiceUrl;

        SimpleClientHttpRequestFactory timeoutFactory = new SimpleClientHttpRequestFactory();
        timeoutFactory.setConnectTimeout(Duration.ofSeconds(2));
        timeoutFactory.setReadTimeout(Duration.ofSeconds(2));
        this.healthClient = RestClient.builder().requestFactory(timeoutFactory).build();
    }

    /**
     * (a) Open, no auth. The account is the "spine" of the response (missing it -> 502); a line whose
     * product lookup fails degrades to a null product on that one line, not a failed request.
     */
    @GetMapping("/orders/{id}/summary")
    public ResponseEntity<?> orderSummary(@PathVariable Long id) {
        Order order;
        try {
            order = orderingClient.getRepositoryForType(Order.class).findOne(id, new QuerySpec(Order.class));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(404).body(error("order " + id + " not found"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(502).body(error("ordering-service unavailable"));
        }

        List<OrderLine> lines;
        try {
            lines = orderingClient.getRepositoryForType(Order.class, OrderLine.class)
                    .findManyTargets(id, "lines", new QuerySpec(OrderLine.class));
        } catch (RuntimeException e) {
            // Same backend that just answered the order itself; treat as a degraded detail rather than
            // failing the whole response, consistent with this endpoint's partial-degradation contract.
            lines = List.of();
        }

        List<LineView> lineViews = lines.stream()
                .map(line -> new LineView(line.getQty(), fetchProductView(line.getProductId())))
                .toList();

        Account account;
        try {
            account = accountsClient.getRepositoryForType(Account.class)
                    .findOne(order.getAccountId(), new QuerySpec(Account.class));
        } catch (RuntimeException e) {
            return ResponseEntity.status(502).body(error("accounts-service unavailable"));
        }

        OrderSummary summary = new OrderSummary(order.getId(), order.getOrderNumber(), toAccountView(account), lineViews);
        return ResponseEntity.ok(summary);
    }

    /** Degraded detail lookup for (a): null product, never a failed request, on catalog-service trouble. */
    private ProductView fetchProductView(Long productId) {
        try {
            return toProductView(catalogClient.getRepositoryForType(Product.class)
                    .findOne(productId, new QuerySpec(Product.class)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * (b) Requires HTTP Basic auth - enforced by SecurityConfig, nothing to check here. Validates the
     * account and every line's product against their real backends before creating anything (so a bad id
     * in line 3 doesn't leave orphan order lines behind from lines 1-2), then creates the order, then each
     * order line with its orderId set to the just-created order's real id - the only mechanism confirmed
     * live to actually persist the link for this entity's particular JPA mapping (see OrderLine.java's
     * javadoc, and task-4-report.md's fix-round-2 section for the live curl output proving it; pushing
     * relationship data through Order's own "lines" field at create time does NOT persist it).
     * <p>
     * Every entity in this workspace has a plain @Id with no @GeneratedValue - this gateway has no way to
     * see ordering-service's own id sequence, so it mints ids itself (see {@link #nextId()}).
     */
    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        List<CreateLineRequest> requestedLines = request.lines() == null ? List.of() : request.lines();

        Account account;
        try {
            account = accountsClient.getRepositoryForType(Account.class)
                    .findOne(request.accountId(), new QuerySpec(Account.class));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(400).body(error("unknown account id " + request.accountId()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(502).body(error("accounts-service unavailable"));
        }

        // Validate every line's product up front - never trust a client-supplied price (there is none on
        // the wire to trust in the first place: CreateLineRequest has no price field at all), always the
        // real price fetched fresh from catalog-service. Validating all lines before creating any of them
        // also avoids leaving orphan order lines behind in ordering-service if a later line turns out bad.
        List<Product> products = new ArrayList<>();
        for (CreateLineRequest lineReq : requestedLines) {
            try {
                products.add(catalogClient.getRepositoryForType(Product.class)
                        .findOne(lineReq.productId(), new QuerySpec(Product.class)));
            } catch (ResourceNotFoundException e) {
                return ResponseEntity.status(400).body(error("unknown product id " + lineReq.productId()));
            } catch (RuntimeException e) {
                return ResponseEntity.status(502).body(error("catalog-service unavailable"));
            }
        }

        // The order and its lines are 2+ independent HTTP calls, not one transaction - if anything from
        // here on fails (a later line, or the order-create itself), best-effort delete whatever already
        // got created in this request rather than leaving it behind (see deleteBestEffort).
        List<OrderLine> createdLines = new ArrayList<>();
        List<LineView> lineViews = new ArrayList<>();
        Order createdOrder = null;
        try {
            Order newOrder = new Order();
            newOrder.setId(nextId());
            newOrder.setOrderNumber("ORD-" + UUID.randomUUID());
            newOrder.setAccountId(request.accountId());
            createdOrder = orderingClient.getRepositoryForType(Order.class).create(newOrder);

            for (int i = 0; i < requestedLines.size(); i++) {
                CreateLineRequest lineReq = requestedLines.get(i);
                OrderLine line = new OrderLine();
                line.setId(nextId());
                line.setQty(lineReq.qty());
                line.setProductId(lineReq.productId());
                line.setOrderId(createdOrder.getId());
                createdLines.add(orderingClient.getRepositoryForType(OrderLine.class).create(line));
                lineViews.add(new LineView(lineReq.qty(), toProductView(products.get(i))));
            }

            OrderSummary summary = new OrderSummary(
                    createdOrder.getId(), createdOrder.getOrderNumber(), toAccountView(account), lineViews);
            return ResponseEntity.status(201).body(summary);
        } catch (RuntimeException e) {
            deleteBestEffort(createdOrder, createdLines);
            return ResponseEntity.status(502).body(error("ordering-service unavailable"));
        }
    }

    /**
     * Mints an id for a new Order/OrderLine: every entity in this workspace has a plain @Id with no
     * @GeneratedValue, and this gateway has no visibility into ordering-service's own id sequence (or
     * whether it even has one, as opposed to a natural key) - the same "client has to mint an id for a
     * resource it's about to create" problem the library's own reference examples solve the same way.
     * Collision risk under real concurrent load is a known, accepted limitation at this demo's scale, not
     * something this gateway tries to solve properly (that would need a shared id allocator, or switching
     * ordering-service itself to database-generated ids).
     */
    private static Long nextId() {
        return System.currentTimeMillis();
    }

    /**
     * Compensating cleanup for createOrder's order-then-lines creation sequence: not an atomic
     * transaction, so a failure partway through (a later line, or the order-create itself failing before
     * any lines exist) can leave an order with missing/partial lines, or lines with nothing pointing at
     * any order. Best-effort only - a failure deleting something here is swallowed rather than thrown,
     * since the caller is already reporting the original failure and a cleanup failure shouldn't mask or
     * replace it.
     */
    private void deleteBestEffort(Order createdOrder, List<OrderLine> createdLines) {
        for (OrderLine line : createdLines) {
            try {
                orderingClient.getRepositoryForType(OrderLine.class).delete(line.getId());
            } catch (RuntimeException ignored) {
                // best effort only - nothing more to do here
            }
        }
        if (createdOrder != null) {
            try {
                orderingClient.getRepositoryForType(Order.class).delete(createdOrder.getId());
            } catch (RuntimeException ignored) {
                // best effort only - nothing more to do here
            }
        }
    }

    /**
     * (c) Open. Deliberately uses no crnk client at all - plain RestClient + plain Jackson JsonNode, to
     * prove the JSON:API wire format needs no crnk-specific client to read, just a generic HTTP+JSON one.
     * <p>
     * The brief's own description of this endpoint expected classic Jackson 2
     * (com.fasterxml.jackson.databind) here as "this gateway's own internal Jackson, unrelated to crnk's
     * Jackson lines" - but this project's exact pom.xml (no new dependency added beyond it) never pulls in
     * jackson-databind 2.x at all: spring-boot-starter-web's spring-boot-starter-jackson module has no
     * hard dependency on either Jackson major version (confirmed via dependency:tree), and auto-configures
     * against whichever databind flavor is actually on the classpath - here, only tools.jackson.databind
     * 3.2.3, pulled in transitively by crnk-client-jackson3. So this uses tools.jackson instead. The
     * architectural point the brief was making is unaffected by which Jackson major version: this
     * ObjectMapper is Spring's own autowired bean for this controller's generic JSON handling, a
     * completely separate instance/pipeline from the private ObjectMapper CrnkClient builds and owns
     * internally for JSON:API document mapping - not the same mapper, regardless of major version.
     */
    @GetMapping("/raw/accounts/{id}")
    public ResponseEntity<?> rawAccount(@PathVariable Long id) {
        ResponseEntity<?> unavailable = ResponseEntity.status(502).body(error("accounts-service unavailable"));
        return withDeadline(() -> {
            try {
                String body = rawClient.get()
                        .uri(accountsServiceUrl + "/account/{id}", id)
                        .header("Accept", "application/vnd.api+json")
                        .retrieve()
                        .body(String.class);
                JsonNode node = objectMapper.readTree(body);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", node.at("/data/attributes/name").asText());
                result.put("email", node.at("/data/attributes/email").asText());
                result.put("plan", node.at("/data/attributes/plan").asText());
                return ResponseEntity.ok(result);
            } catch (Exception e) {
                return unavailable;
            }
        }, unavailable);
    }

    /**
     * (d) Open. Always 200 - per-backend status is data in the body, never a failed request. The 2s
     * timeout on healthClient, plus the outer withDeadline() wrapper below, are what keep this from
     * hanging when a backend container is stopped.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("accounts", withDeadline(() -> checkHealth(accountsServiceUrl), "UNREACHABLE"));
        result.put("catalog", withDeadline(() -> checkHealth(catalogServiceUrl), "UNREACHABLE"));
        result.put("ordering", withDeadline(() -> checkHealth(orderingServiceUrl), "UNREACHABLE"));
        return ResponseEntity.ok(result);
    }

    /**
     * Bounds a backend call to a hard 2s wall-clock deadline, independent of whatever timeout the HTTP
     * client used inside `call` is itself configured with (healthClient's own connect/read timeouts,
     * rawClient's lack of any). Needed because, against a real stopped Docker container - as opposed to a
     * port nothing ever listens on, which is all the fake-server/unit tests in this project can exercise -
     * hostname resolution for the stopped container's own service name can itself block for far longer
     * than any configured connect/read timeout: confirmed live against this exact docker-compose stack
     * (catalog-service stopped, ~10s instead of the configured 2s) - a well-known JDK limitation,
     * URLConnection's connectTimeout bounds the TCP connect phase only, not the DNS resolution that
     * precedes it. Runs `call` on a virtual thread; if the deadline passes, the caller gets `onTimeout`
     * immediately and moves on - the abandoned call is left to finish in the background and its result is
     * simply discarded, since plain JDK networking calls aren't cleanly interruptible mid-DNS-lookup.
     */
    private <T> T withDeadline(Supplier<T> call, T onTimeout) {
        try {
            return CompletableFuture.supplyAsync(call, boundedCallExecutor)
                    .completeOnTimeout(onTimeout, 2, TimeUnit.SECONDS)
                    .join();
        } catch (RuntimeException e) {
            return onTimeout;
        }
    }

    private String checkHealth(String baseUrl) {
        try {
            String body = healthClient.get().uri(baseUrl + "/actuator/health").retrieve().body(String.class);
            return objectMapper.readTree(body).path("status").asText("UNKNOWN");
        } catch (RestClientResponseException e) {
            // Got a real HTTP response (actuator reports DOWN as e.g. HTTP 503) - still a parseable body.
            try {
                return objectMapper.readTree(e.getResponseBodyAsString()).path("status").asText("UNKNOWN");
            } catch (Exception unparseable) {
                return "UNREACHABLE";
            }
        } catch (Exception e) {
            // Timeout, connection refused, DNS failure, etc. - the backend is simply not answering.
            return "UNREACHABLE";
        }
    }

    private static ProductView toProductView(Product p) {
        return new ProductView(p.getId(), p.getSku(), p.getName(), p.getPrice());
    }

    private static AccountView toAccountView(Account a) {
        return new AccountView(a.getId(), a.getName(), a.getEmail(), a.getPlan());
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    record OrderSummary(Long orderId, String orderNumber, AccountView account, List<LineView> lines) {}

    record AccountView(Long id, String name, String email, String plan) {}

    record LineView(int qty, ProductView product) {}

    record ProductView(Long id, String sku, String name, BigDecimal price) {}

    record CreateOrderRequest(Long accountId, List<CreateLineRequest> lines) {}

    record CreateLineRequest(Long productId, int qty) {}
}
