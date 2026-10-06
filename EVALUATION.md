# Evaluation

This document presents findings from an actual, live run of the full stack: `docker compose build` ->
`docker compose up -d` (all 7 containers) -> `.\verify.ps1` -> `docker compose logs` inspection -> `docker
compose down -v`. Every status code, response body, timing number, and log line below was captured from
that run (two consecutive full runs, in fact — both produced the identical **39 passed, 5 failed** result).
Nothing here is paraphrased or invented; see `verify.ps1` to reproduce every number directly.

## 1. Builds & runs successfully

All 4 projects built cleanly with `mvn clean package -DskipTests` (tests already passed within each
project's own task; this run only needed the jar):

| Project | Toolchain confirmed | Result |
|---|---|---|
| accounts-service | `Found matching toolchain for type jdk: JDK[D:\Java\java21]` | `BUILD SUCCESS`, 22.1s |
| catalog-service | `Found matching toolchain for type jdk: JDK[D:\Java\java21]` | `BUILD SUCCESS`, 13.3s |
| ordering-service | `Found matching toolchain for type jdk: JDK[D:\Java\java25]` | `BUILD SUCCESS`, 12.4s |
| storefront-gateway | `Found matching toolchain for type jdk: JDK[D:\Java\java25]` | `BUILD SUCCESS`, 5.9s |

`docker compose build` built all 4 images without error (`eclipse-temurin:21-jre` for the Boot2/Boot3
services, `eclipse-temurin:25-jre` for the Boot4 services). `docker compose up -d` brought up all 7
containers (3 Postgres 16, 4 app containers); `docker compose ps` confirmed all 7 `running` within the wait
window, and every one of accounts/catalog/ordering's own `/actuator/health` plus the gateway's `/api/health`
returned HTTP 200 on the very first poll (3 seconds after `up -d` returned, no retries needed).

## 2. Wire-protocol integrity across versions

The 4 designed cross-generation **read** relationships (`?include=`) all passed, each a real HTTP round
trip between two different Spring Boot majors (and, for ordering <-> everything, a crossing between the
classic Jackson 2 wire format used by accounts/catalog and the `tools.jackson` 3 format used by
ordering/gateway):

```
[PASS] catalog(Boot3) -> accounts(Boot2): product/1?include=owner (HTTP 200)
[PASS] included owner matches account 1 (found 'Alice Nguyen')
[PASS] ordering(Boot4) -> catalog(Boot3): orderLine/1?include=product (HTTP 200)
[PASS] included product matches product 1 (found 'SKU-001')
[PASS] ordering(Boot4) -> accounts(Boot2), skipping Boot3 entirely: order/1?include=account (HTTP 200)
[PASS] included account matches account 1 (found 'Alice Nguyen')
[PASS] accounts(Boot2) -> ordering(Boot4), skipping Boot3, REVERSE direction: account/1?include=orders (HTTP 200)
[PASS] included orders contains order 1 (found '"id":"1","type":"order"')
```

The 4th of these (reverse, skip-generation, Boot2 querying Boot4 directly) is the headline new coverage this
workspace set out to prove: `Account.orders` is backed by nothing but a remote `filter[account.id]=X` call
against ordering-service's `Order` resource (no local JPA association at all, see
`accounts-service/.../OrderLinkerModule.java`), and it resolved correctly end-to-end.

### Headline finding: relationship-by-id-on-**create** does not work for this one-to-many shape

The one relationship mechanism verify.ps1 could not confirm working is **writing** a to-many relationship
at create time — specifically, Task 4's flagged open concern about `storefront-gateway`'s `POST
/api/orders`. The real, live result:

```
[FAIL] POST /api/orders with valid admin credentials -> 201 (expected HTTP 201, got 502)
       Body: {"error":"ordering-service unavailable"}
```

This traces to **two distinct, compounding findings**, both confirmed directly against the live stack
(bypassing the gateway entirely for the second one, to isolate the two questions):

**Finding A — storefront-gateway never assigns an id before calling `create()`.** Every entity in this
workspace (`Account`, `Product`, `Order`, `OrderLine`) declares `@Id` with no `@GeneratedValue` strategy —
confirmed live and consistent across all three crnk services; every other creator in the codebase (each
service's own test suite, every seed initializer, and this very `verify.ps1`, once its own sections 1/5 were
corrected to match) always supplies an explicit id. `storefront-gateway`'s `createOrder()` is the one place
that does not:
`storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/StorefrontController.java:176`
(`OrderLine line = new OrderLine();`, no `.setId(...)`) and `:183` (`Order newOrder = new Order();`, same
gap). Confirmed directly against ordering-service:

```
POST /orderLine (no id)  -> 500 {"errors":[{"status":"500","title":"Identifier of entity
  'io.github.doantrantuandat.example.ordering.OrderLine' must be manually assigned before calling 'persist()'"}]}
```

`createOrder`'s broad `catch (RuntimeException e)` (the same block that already does the best-effort
orphan-line cleanup from Task 4's earlier fix round) maps this to the generic `502 "ordering-service
unavailable"` the client actually sees — a misleading message, since ordering-service is fully up; it is
correctly rejecting an id-less create.

**Finding B — even with a valid id supplied, relationship-by-id-on-create for `Order.lines` does not take
effect.** Tested independently, directly against ordering-service, completely sidestepping Finding A by
supplying explicit ids by hand:

```
POST /orderLine {id:559047, qty:3, product:1}      -> 201, order: null
POST /order {id:559048, lines:[{orderLine,559047}]} -> 201, but lines: [] in the response
GET  /order/559048?include=lines                     -> 200, lines: [] (still empty)
GET  /orderLine/559047                               -> 200, order: null (still unlinked)
```

The order is created successfully, but the `order_id` column backing `OrderLine.orderId` is never written
through the parent's create-time relationship data. Root cause, read directly from
`ordering-service/.../OrderLine.java:57-60`: the JPA mapping uses a "shared join column" pattern
(`@ManyToOne @JoinColumn(name = "order_id", insertable = false, updatable = false)` paired with a plain,
separately-writable `orderId` scalar field) — a legitimate, common JPA idiom for exposing both a scalar FK
and a read-only navigational association, but one that `Order.java:65-67`'s `@OneToMany(mappedBy = "order")`
cannot write through, because `mappedBy` designates `OrderLine.order` as the owning field for persistence
purposes, and that exact field is marked non-writable.

A follow-up probe narrows this further and rules out "crnk-data-jpa can't write this relationship at all" as
too broad a conclusion:

```
PATCH /order/559048/relationships/lines  {data:[{orderLine,559047}]}  -> 500 "not yet implemented"
PATCH /orderLine/559047/relationships/order  {data:{order,559048}}    -> 204, and it WORKS:
GET /orderLine/559047  -> order: {id: 559048}  (correctly linked)
GET /order/559048?include=lines -> lines: [{id: 559047}]  (correctly linked, confirmed from both sides)
```

So: crnk-data-jpa's dedicated to-many relationship-update endpoint for this mapping shape is **not
implemented** (its own error message, verbatim); the to-one direction from the owning child
(`OrderLine.order`, backed by the plain writable `orderId` column) **does** work correctly via its own
relationship endpoint. The one path that does not work, under any mechanism tested, is writing the
`Order.lines` collection — which is exactly the one mechanism `storefront-gateway`'s `createOrder()` relies
on. `verify.ps1`'s assertion here was deliberately left expecting `201` (not loosened to `502`) so this
failure stays visible rather than being papered over — this is a genuine finding about already-reviewed code
in two other tasks (`storefront-gateway`'s id-assignment gap, and this specific JPA-mapping/crnk-data-jpa
interaction), not a defect in this integration task's own work, and neither was modified to "fix" it per this
task's explicit scope boundary.

## 3. Security assurance

```
[PASS] POST product with no X-Mock-Role -> 403 (HTTP 403)
[PASS] POST /api/orders with no credentials -> 401 (HTTP 401)
[PASS] filter[accountId] (raw relation-id, not traversed) -> 400 UNKNOWN_PARAMETER (HTTP 400)
[PASS] filter[account.id] (correctly traversed) -> 200 (regression check) (HTTP 200)
[PASS] blank name + invalid email -> 422 (HTTP 422)
[PASS] broken accountId=99999 -> clean 404, not 500 (HTTP 404)
```

Two independent, deliberately-different auth mechanisms both behave correctly: MockAuth
(`X-Mock-Role` header, gating `create`/`delete` on each service's primary resource only — `OrderLine` is
intentionally left open) returns a proper JSON:API error document at 403, not a raw stack trace; the
gateway's real Spring Security 7.1.1 HTTP Basic filter chain returns 401 before any backend is even
contacted. A relationship-id path is correctly rejected as an unfilterable raw column (`400
UNKNOWN_PARAMETER`) while the equivalent relation-traversal path resolves (`200`) — confirmed as a
regression check, not a fresh guess, per Task 3's own prior finding. A reference to a nonexistent account
(`id=99999`) degrades to a clean `404`, never a `500` or a raw exception body.

## 4. Backward/forward compatibility & versioning discipline

The library's own `CHANGELOG.md` (`D:\Projects\crnk-framework\CHANGELOG.md`) documents exactly two Maven
Central releases under this coordinate:

> **4.0.0-lts.2** — First complete Maven Central release. Publishes every module listed in the README's
> module map, including a full `crnk-bom` covering all three generation lines... This is the recommended
> version.
>
> **4.0.0-lts.1 — incomplete, do not use** — Trial release... Contains only `crnk-core`,
> `crnk-core-jackson3`, a partial `crnk-bom`... every other module... is missing. Maven Central publications
> are immutable, so this version cannot be deleted... Use `4.0.0-lts.2` or later instead.

This workspace targets `4.0.0-lts.2` exclusively (via `crnk-bom` import, never a hand-pinned individual
crnk artifact version, in all 4 projects), which is the correct, deliberate choice per the library's own
guidance. This run independently re-confirms Task 3's own finding from its own task: `mvn dependency:tree`
against all three generation lines (see criterion 5 below) resolved every single `crnk-*` artifact at
exactly `4.0.0-lts.2`, fetched fresh from Maven Central with zero local-build workarounds, zero
`mvn install`-ed reactor artifacts, and zero version overrides anywhere in any of the 4 `pom.xml` files — a
genuine, unassisted external-consumer resolution of the complete, intended release across all three Spring
Boot generations simultaneously.

## 5. Dependency/supply-chain hygiene

`mvn dependency:tree` run on one project per generation line, all three completely clean (zero `omitted for
conflict` lines, zero duplicate/divergent versions of any `crnk-*` artifact):

**accounts-service (Boot2/javax/legacy generation)**:
```
+- io.github.doantrantuandat:crnk-setup-spring-boot2:jar:4.0.0-lts.2:compile
|  +- io.github.doantrantuandat:crnk-setup-servlet-legacy:jar:4.0.0-lts.2:compile
|  \- io.github.doantrantuandat:crnk-setup-spring:jar:4.0.0-lts.2:compile
|     \- io.github.doantrantuandat:crnk-setup-servlet:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-data-jpa-legacy:jar:4.0.0-lts.2:compile
|  +- io.github.doantrantuandat:crnk-core:jar:4.0.0-lts.2:compile
|  \- io.github.doantrantuandat:crnk-meta:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-validation-legacy:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-client:jar:4.0.0-lts.2:compile
```

**catalog-service (Boot3/jakarta/Jackson2 generation)**:
```
+- io.github.doantrantuandat:crnk-setup-spring-boot3:jar:4.0.0-lts.2:compile
|  +- io.github.doantrantuandat:crnk-setup-servlet:jar:4.0.0-lts.2:compile
|  \- io.github.doantrantuandat:crnk-setup-spring:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-data-jpa:jar:4.0.0-lts.2:compile
|  +- io.github.doantrantuandat:crnk-core:jar:4.0.0-lts.2:compile
|  \- io.github.doantrantuandat:crnk-meta:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-validation:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-client:jar:4.0.0-lts.2:compile
```

**ordering-service (Boot4/jakarta/Jackson3 generation)**:
```
+- io.github.doantrantuandat:crnk-setup-spring-boot4:jar:4.0.0-lts.2:compile
|  +- io.github.doantrantuandat:crnk-setup-servlet-jackson3:jar:4.0.0-lts.2:compile
|  \- io.github.doantrantuandat:crnk-setup-spring-jackson3:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-data-jpa-jackson3:jar:4.0.0-lts.2:compile
|  +- io.github.doantrantuandat:crnk-core-jackson3:jar:4.0.0-lts.2:compile
|  \- io.github.doantrantuandat:crnk-meta-jackson3:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-validation-jackson3:jar:4.0.0-lts.2:compile
+- io.github.doantrantuandat:crnk-client-jackson3:jar:4.0.0-lts.2:compile
```

Each generation line correctly pulls its own matching module family (`-legacy` for Boot2/javax, plain for
Boot3/jakarta+Jackson2, `-jackson3` for Boot4/jakarta+Jackson3) with no cross-contamination between
families and no transitive pull of an unrelated generation's modules. Framework-level dependencies resolve
to the versions each generation's own `spring-boot-starter-parent` manages (Spring Security 7.1.1 under Boot
4.1.1, Hibernate 5.4.32 under Boot 2.3.12, Hibernate 6.6.53 under Boot 3.5.16, etc.) with no manual
overrides anywhere.

**Not run** (stretch goal, explicitly out of scope for this task): OWASP `dependency-check-maven`. The NVD
CVE feed this plugin downloads on first run is large and rate-limited by NIST's own API, making it a poor
fit for a single bounded task session; it would be a reasonable follow-up for anyone maintaining this
workspace longer-term.

## 6. Performance/resource overhead

Real Spring Boot startup-time log lines, captured from `docker compose logs` (cold start, i.e. the first
boot against an empty, freshly-created Postgres schema):

| Service | Startup time (first boot) |
|---|---|
| accounts-service | `Started AccountsServiceApplication in 7.0 seconds (JVM running for 7.372)` |
| catalog-service | `Started CatalogServiceApplication in 7.83 seconds (process running for 8.502)` |
| ordering-service | `Started OrderingServiceApplication in 8.343 seconds (process running for 9.137)` |
| storefront-gateway | `Started StorefrontGatewayApplication in 4.453 seconds (process running for 5.522)` |

(Subsequent restarts, observed during the resilience tests in criterion 7 below, were consistently faster —
accounts-service restarted in `3.045`-`3.395` seconds and catalog-service in `3.429`-`3.445` seconds once the
OS page cache was warm and the schema already existed, versus the cold-start numbers above.)

Real request timing (`curl -w "%{time_total}"`, 3 consecutive requests each, against ordering-service):

| Request | Attempt 1 | Attempt 2 | Attempt 3 |
|---|---|---|---|
| `GET /order/1` (plain read, no relations resolved) | 10.5ms | 7.1ms | 7.6ms |
| `GET /order/1?include=account` (1 cross-service hop to accounts-service) | 28.4ms | 20.7ms | 18.7ms |
| `GET /order/1?include=account,lines` (cross-service hop + local JPA join) | 18.4ms | 18.3ms | 19.2ms |

A single-relation cross-service `include` costs roughly 2-3x a plain read (the extra outbound HTTP hop to
accounts-service dominates), while adding the second, purely-local `lines` relation on top costs
essentially nothing extra (same ballpark as the single-hop case, since `lines` is a same-database JPA join,
not another network call) — the overhead scales with cross-service hops, not relation count.

## 7. Failure resilience / graceful degradation

**Scenario 12 — stop catalog-service** (an optional-detail dependency for the gateway's order summary):

```
[PASS] summary still 200 with catalog down (product detail degrades, account is unaffected) (HTTP 200)
[FAIL] health reports catalog down (expected to find '"catalog":"DOWN"')
       Body: {"accounts":"UP","catalog":"UNREACHABLE","ordering":"UP"}
[PASS] catalog correctly reported non-UP while stopped
```

The order summary endpoint correctly kept returning `200` with the product line degrading rather than
failing the whole request. The one `[FAIL]` here is a benign, cosmetic mismatch in `verify.ps1`'s own first,
strict assertion (it looks for the literal string `"DOWN"`, but a fully-stopped container is reported as
`"UNREACHABLE"` by the gateway's `checkHealth()` — a real, meaningful distinction between "actuator
responded and said it's unhealthy" vs. "nothing answered at all" — not a bug). The script's own very next
check already anticipates exactly this and accepts either value, which is why the section still nets an
overall correct result (`[PASS] catalog correctly reported non-UP while stopped`). Left as-is rather than
"fixed" into a silent pass, since the brief's own script was clearly already designed this way (the
accepting fallback check immediately follows the strict one) and the strict check failing here is itself
informative, accurate evidence of which of the two states actually occurred.

**Scenario 13 — stop accounts-service** (a hard dependency for both catalog's `owner` relation and the
gateway's order summary):

```
[PASS] catalog's fail-open wrapper: clean 404 (not a hang/500) with accounts-service down (HTTP 404)
[PASS] gateway summary endpoint did not hang/crash with accounts-service down (got HTTP 502)
```

Both consumers of accounts-service degrade cleanly: catalog-service's `AccountLinkerModule`'s fail-open
`RemoteAccountRepository` wrapper converts the connection failure into a clean `404` (never a raw
`500`/timeout), and the gateway's own 2-second-timeout `healthClient`/orchestration logic returns a prompt
`502` rather than hanging. Both services were restarted (`docker compose start ...`) immediately after each
scenario and confirmed back to normal before the next section ran.

## 8. API ergonomics/extensibility

Adding a new forward, by-id-only cross-service relation (no local-side lookup/decorator population needed,
the simplest realistic case in this codebase) is a ~25-30-line, two-part change, illustrated by the actual
committed `ordering-service/.../ProductLinkerModule.java` (67 lines total):

- **The relation-specific part** (~25-30 lines): a `CrnkClient` field + 2-line constructor, `getModuleName()`
  (1 line), `setupModule()` registering one wrapped remote repository (1 line), plus the matching
  `@JsonApiRelationId`/`@JsonApiRelation` field pair on the owning entity (`OrderLine.productId`/`product` in
  `OrderLine.java`) — squarely within the brief's own ~25-40-line estimate.
- **The reusable part** (~30 lines): the `WrappedResourceRepository` fail-open wrapper (`findOne`/`findAll`/
  `findAll(ids)`, each catching `RuntimeException` and degrading to a 404 or empty list) is boilerplate
  repeated near-identically across all three of this codebase's linker modules (`OrderLinkerModule`,
  `AccountLinkerModule`, `ProductLinkerModule`) — copy-pasteable per relation today, and a natural candidate
  for extraction into a small shared base class if this workspace ever grew past 3 relations.

## 9. Observability

Each crnk service exposes `management.endpoints.web.exposure.include=health` (Spring Boot Actuator), and
the gateway folds all three into one aggregate endpoint with a 2-second timeout per backend so a dead
backend can never make the health check itself hang. Real captured output:

All backends healthy:
```
GET /api/health -> 200 {"accounts":"UP","catalog":"UP","ordering":"UP"}
```

With catalog-service stopped (scenario 12):
```
GET /api/health -> 200 {"accounts":"UP","catalog":"UNREACHABLE","ordering":"UP"}
```

The endpoint itself always returns `200` regardless of backend state (per-backend status is data in the
body, never a failed request) — confirmed in both the all-up and one-down cases above.

## 10. Concurrency/thread-safety under load

```
[PASS] 20/20 concurrent requests returned 200
```

20 parallel PowerShell background jobs, each independently curling
`ordering-service`'s `GET /order/1?include=account` (a cross-service, relationship-resolving read — the
most contended realistic endpoint in this workspace) at the same time: all 20 returned `200` with no
errors, timeouts, or connection resets, against the default Hikari/Tomcat thread pool sizing (no tuning
applied in any project).

## Summary

```
=== SUMMARY: 39 passed, 5 failed ===
```

Reproduced identically across two consecutive full runs. Of the 5 failures: 4 trace to the single
relationship-linkage-on-create finding in criterion 2 above (one root cause, cascading through the create
call's own assertion plus the 3 follow-on assertions that depend on a successful create), and 1 is the
benign `"DOWN"`-vs-`"UNREACHABLE"` cosmetic distinction in criterion 7, which the script's own design already
compensates for with a passing fallback check. Every other exercised behavior in this workspace — all 4
cross-generation relationship reads, both auth mechanisms, validation, the broken-reference negative case,
content negotiation, gateway orchestration, dependency resolution, startup/request timing, two independent
failure-injection scenarios, and 20-way concurrency — passed cleanly on real, live HTTP calls against the
full 7-container stack.
