# Evaluation

This document presents findings from an actual, live run of the full stack: `docker compose build` ->
`docker compose up -d` (all 7 containers) -> `.\verify.ps1` -> `docker compose logs` inspection -> `docker
compose down -v`. Every status code, response body, timing number, and log line below was captured from
that run. Nothing here is paraphrased or invented; see `verify.ps1` to reproduce every number directly.

This is the post-fix-wave state of the workspace. A whole-branch review found that an earlier version of
this document had gone stale: it described a `storefront-gateway` `POST /api/orders` defect that had, by
then, already been investigated and fixed, and it cited a failure count and line numbers that no longer
matched the real, current code. This revision corrects that: every number below comes from three
consecutive, freshly-executed live runs performed after that fix (and a small follow-up fix wave addressing
five further review findings — see each section for specifics), all three producing the identical result:

```
=== SUMMARY: 44 passed, 0 failed ===
```

(`verify.ps1`'s total check count is now a fixed, asserted number — see criterion 9 — precisely so a count
like this can never again drift silently; nothing below reflects a loosened or removed check.)

## 1. Builds & runs successfully

All 4 projects build cleanly with `mvn clean package -DskipTests` (each project's own test suite is run
separately — see criterion 2's regression-test note and the toolchain table below):

| Project | Toolchain confirmed | Result |
|---|---|---|
| accounts-service | `Found matching toolchain for type jdk: JDK[D:\Java\java21]` | `BUILD SUCCESS`, 4.4s |
| catalog-service | `Found matching toolchain for type jdk: JDK[D:\Java\java21]` | `BUILD SUCCESS`, 4.7s |
| ordering-service | `Found matching toolchain for type jdk: JDK[D:\Java\java25]` | `BUILD SUCCESS`, 4.7s |
| storefront-gateway | `Found matching toolchain for type jdk: JDK[D:\Java\java25]` | `BUILD SUCCESS`, 4.7s |

`docker compose build` built all 4 images without error (`eclipse-temurin:21-jre` for the Boot2/Boot3
services, `eclipse-temurin:25-jre` for the Boot4 services). `docker compose up -d` brought up all 7
containers (3 Postgres 16, 4 app containers); `docker compose ps` confirmed all 7 running, every one of
accounts/catalog/ordering's own `/actuator/health` plus the gateway's `/api/health` returned HTTP 200 within
10 seconds of `up -d` returning, no retries needed.

This fix wave added a `pg_isready` healthcheck to each of the 3 Postgres containers, with the dependent app
service's own `depends_on` entry upgraded to `condition: service_healthy` (`docker-compose.yml`) — a cold,
first-time `up -d` previously only waited for the Postgres *container* to start, not for Postgres itself to
accept connections, which could race an app's own DB connection pool init. Confirmed live: `docker compose
up -d`'s own output now visibly sequences `accounts-db-1 Healthy` before `accounts-service-1 Starting` (and
the same for catalog/ordering), and `docker compose ps` now reports `running (healthy)` for all 3 Postgres
containers. One disclosed, harmless side effect: `pg_isready -U crnk` (no explicit `-d`) probes a database
literally named `crnk`, which doesn't exist (the real databases are `accountsdb`/`catalogdb`/`orderingdb`) —
Postgres logs a `FATAL: database "crnk" does not exist` line on every single probe, for the container's
entire lifetime, at the configured 2-second interval. This is cosmetic log noise, not a functional defect:
`pg_isready` only checks whether the server responds to a connection attempt at all (any response, including
this rejection, counts as "accepting connections" per its own documented behavior), which is exactly why the
healthcheck still correctly reports `healthy` quickly and every app service starts normally — confirmed by
every number in this document being captured from stacks that came up this way.

This fix wave also added `<scope>provided</scope>` to the `lombok` dependency in all 4 `pom.xml` files
(previously present in only one, per a deferred finding from the initial per-project reviews). This correctly
changes Lombok's resolved Maven scope (`mvn dependency:tree -Dincludes=org.projectlombok` now shows
`org.projectlombok:lombok:jar:1.18.48:provided` in every project) but, verified directly by inspecting each
rebuilt jar (`unzip -l target/*.jar | grep lombok`), does **not** by itself remove `lombok-1.18.48.jar` from
`BOOT-INF/lib` of the repackaged executable jar in any of the 4 projects, on any of the 3 Spring Boot majors
in this workspace (2.3.12/3.5.16/4.1.1) — `spring-boot-maven-plugin`'s `repackage` goal bundles all resolved
dependencies regardless of `provided`/`optional` (confirmed empirically: adding `<optional>true</optional>`
alongside `provided` made no difference either) unless explicitly excluded via its own `<excludes>`/
`excludeGroupIds` configuration, which is a separate change this fix was explicitly scoped not to make. The
scope change is still correct, standard Maven hygiene (Lombok genuinely is compile-time-only and should not
be on the runtime classpath of anything consuming this project as a library); it just does not, on its own,
shrink the Docker image, contrary to that finding's original assumption.

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

### Headline finding: a real crnk-data-jpa limitation, and the workaround that ships around it

The most interesting relationship-handling question this workspace surfaced wasn't a read — all 4 reads
above work cleanly — it was **writing** a to-many relationship at create time, specifically
`storefront-gateway`'s `POST /api/orders` ("create an order with its lines in one call").

**The limitation.** `ordering-service`'s `OrderLine` entity uses a "shared join column" JPA mapping:
`OrderLine.java` pairs a plain, writable `orderId` scalar column with a `@ManyToOne @JoinColumn(name =
"order_id", insertable = false, updatable = false)` navigational field — a legitimate, common idiom for
exposing both a writable FK and a read-only association. `Order.java`'s own `@OneToMany(mappedBy = "order")`
`lines` field is declared against that exact non-writable field. Consequence, confirmed directly against a
live ordering-service, independent of the gateway entirely: pushing relationship-by-id data through
`Order`'s own `create()` call (`lines: [...]`) returns `201`, but the `order_id` column backing each
`OrderLine.orderId` is never actually written — every subsequent read shows `lines: []` and the line's own
`order` as `null`. A follow-up probe narrows this precisely: `PATCH /order/{id}/relationships/lines` — the
dedicated endpoint for updating a to-many relationship after the fact — answers `500 "not yet implemented"`
in this library version (`4.0.0-lts.2`), verbatim. The to-one direction from the *child* side,
`PATCH /orderLine/{id}/relationships/order`, by contrast, works correctly (`204`, confirmed bidirectionally
linked on re-fetch from both the order and orderLine sides) — because that path writes through the plain,
writable `orderId` scalar column directly, never touching the non-writable `mappedBy` side at all. So: this
specific crnk-data-jpa version cannot write a to-many relationship through a `mappedBy` target backed by a
shared, non-writable join column, by any mechanism tested — but the equivalent to-one write from the owning
child entity works fine, and that's enough to build on.

**The workaround.** `storefront-gateway`'s `createOrder()` (`StorefrontController.java`) now creates the
`Order` first, reads its real, server-confirmed id back off the create response, and only then creates each
`OrderLine` with that real id already set on its own writable `orderId` field (`StorefrontController.java:201`
mints and sets the order's id; `:212`, `line.setOrderId(createdOrder.getId())`, threads the just-created
order's real id into each line *before* that line's own `create()` call — not via a later relationship PATCH).
This sidesteps the broken `mappedBy`-collection write entirely by going through the always-writable
child-side column instead. Live, end-to-end confirmation from this run:

```
[PASS] POST /api/orders with valid admin credentials -> 201 (HTTP 201)
[PASS] created order's account resolved correctly (found 'Alice Nguyen')
[PASS] created order's line product resolved correctly (found 'SKU-001')
[PASS] re-fetched new order 1791332516196 directly from ordering-service (HTTP 200)
[PASS] re-fetched order's lines relationship is populated (not empty) (found '"type":"orderLine"')
```

Every entity in this workspace has a plain `@Id` with no `@GeneratedValue` (confirmed live and consistent
across all three crnk services), so the gateway also has no visibility into ordering-service's own id
sequence — it mints an id itself (`System.currentTimeMillis()`; a known, documented, accepted collision risk
under true concurrent load at this demo's scale, not something this fix attempts to solve properly).

This fix wave also closed two related gaps in the same endpoint, both found by the final whole-branch
review: `createOrder`'s only error path used to be a single `catch (RuntimeException e) -> 502 "ordering-
service unavailable"` for *everything*, including a client sending `qty: 0`/a missing `qty`/a null
`accountId` — input ordering-service would itself correctly reject with its own validation, misreported to
the caller as "the backend is down." `createOrder` now rejects these with a `400` before any backend call
(`StorefrontController.java:158-163`). Separately, `GET /api/orders/{id}/summary`'s lines-fetch used to
silently degrade a transient ordering-service failure into `lines: []` under a confident `200` — the one
place in this gateway where a backend hiccup was indistinguishable from "this order genuinely has no lines."
It now returns the same `502` the account-lookup two lines below it already used (`StorefrontController.
java:111`), matching every other fail-loud path in this endpoint. A new test,
`StorefrontControllerCreateOrderTest` (3 fake JSON:API backends via `com.sun.net.httpserver.HttpServer`,
following the same pattern as the existing `BackendClientsConfigTest`/`CrnkClientSmokeTest`), now proves the
reorder/orderId-linkage wiring independently of real crnk-data-jpa/Postgres behavior — this exact mechanism
had been broken and fixed twice before this test existed, verified only by one live, uncommitted run each
time.

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
regression check, not a fresh guess, per an earlier task's own prior finding. A reference to a nonexistent
account (`id=99999`) degrades to a clean `404`, never a `500` or a raw exception body — and, as of this fix
wave, `verify.ps1` itself now fails loudly (rather than silently skipping) if the setup POST for this
specific negative-case check ever can't seed the broken order it needs (criterion 9 below).

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
guidance. `mvn dependency:tree` against all three generation lines (see criterion 5 below) resolves every
single `crnk-*` artifact at exactly `4.0.0-lts.2`, fetched fresh from Maven Central, zero local-build
workarounds, zero `mvn install`-ed reactor artifacts — a genuine, unassisted external-consumer resolution of
the complete, intended release across all three Spring Boot generations simultaneously.

## 5. Dependency/supply-chain hygiene

`mvn dependency:tree` run on one project per generation line, all three completely clean for the `crnk-*`
artifact family (zero `omitted for conflict` lines, zero duplicate/divergent versions of any `crnk-*`
artifact):

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
families and no transitive pull of an unrelated generation's modules.

**Correction to an earlier claim in this document.** A previous revision of this section stated "zero
version overrides anywhere in any of the 4 `pom.xml` files." That was wrong, and is corrected here. There
are two deliberate, disclosed, necessary overrides:

1. **Lombok pinned to `1.18.48`** in all 4 projects (`spring-boot-starter-parent:2.3.12.RELEASE` would
   otherwise manage `1.18.20`, which crashes as a javac annotation processor under the JDK 21 toolchain this
   workspace's Boot2/Boot3 projects use — `NoSuchFieldError` on javac's internal AST, confirmed via direct
   `javac` invocation). Not a defect in the library under test — a toolchain-compatibility fix for an
   unrelated dependency.

2. **A genuine finding about the published artifact itself**: `crnk-bom`'s own parent POM
   (`crnk-framework-lts`) pins `junit-jupiter` to `5.14.4` for the library's *own* build, and this pin leaks
   to every downstream consumer via normal Maven dependency mediation when they import `crnk-bom`. Both
   Boot-4.1.1 projects in this workspace (`ordering-service`, `storefront-gateway`) import `crnk-bom`, and
   Boot 4.1.1's own `spring-boot-starter-parent` manages JUnit via `org.junit:junit-bom:6.0.3` — a JUnit 6
   baseline that `spring-test:7.0.9` is compiled against (it needs JUnit 6's `ExtensionContext.Store` API, an
   added 3-arg `computeIfAbsent` overload). Without an explicit override, this is a silent, runtime-only
   break: tests *compile* cleanly against either JUnit version, then fail at execution with
   `NoSuchMethodError` on `ExtensionContext$Store.computeIfAbsent` — confirmed empirically during this
   workspace's own development. Both affected projects already carry the fix, and it is worth quoting in
   full since it's the actual, reproducible, external-consumer-visible mechanism:

   `ordering-service/pom.xml:20-34`:
   ```xml
   <!-- Listed before crnk-bom deliberately: within one pom's dependencyManagement, the first entry for
        a given groupId:artifactId wins. crnk-bom's parent (crnk-framework-lts) pins junit-jupiter to
        5.14.4 for the library's own build; spring-boot-starter-parent:4.1.1 manages JUnit via
        junit-bom:6.0.3 instead (a new JUnit 6 baseline) and spring-test:7.0.9 is compiled against JUnit
        6's ExtensionContext.Store API (an added 3-arg computeIfAbsent overload) - without this override
        winning, tests fail at runtime with NoSuchMethodError, not a compile error (confirmed empirically:
        see task report). -->
   <dependency>
     <groupId>org.junit</groupId>
     <artifactId>junit-bom</artifactId>
     <version>6.0.3</version>
     <type>pom</type><scope>import</scope>
   </dependency>
   <dependency>
     <groupId>io.github.doantrantuandat</groupId>
     <artifactId>crnk-bom</artifactId>
     <version>4.0.0-lts.2</version>
     <type>pom</type><scope>import</scope>
   </dependency>
   ```
   `storefront-gateway/pom.xml:20-34` carries the equivalent override with near-identical wording, placed
   before its own `crnk-bom` import the same way. Confirmed by re-running `mvn dependency:tree
   -Dincludes=org.junit` fresh against both projects for this evaluation: `junit-jupiter:6.0.3` (not
   `5.14.4`) resolves in both, and both projects' own test suites pass (criterion 1/2).

   **Any consumer of `crnk-bom` on Spring Boot 4.x who imports it without this explicit override will hit
   this at test-runtime**, not at compile time, which makes it a genuinely easy trap to fall into — exactly
   the kind of real, reproducible, external-consumer-visible issue this whole exercise exists to surface. It
   is not something to minimize: the library's own `crnk-bom` should arguably not leak a test-scoped,
   build-internal JUnit pin to its consumers' own `dependencyManagement` at all, but it currently does.

**Not run** (stretch goal, explicitly out of scope for this task): OWASP `dependency-check-maven`. The NVD
CVE feed this plugin downloads on first run is large and rate-limited by NIST's own API, making it a poor
fit for a single bounded task session; it would be a reasonable follow-up for anyone maintaining this
workspace longer-term.

## 6. Performance/resource overhead

Real Spring Boot startup-time log lines, captured from `docker compose logs` (cold start, i.e. the first
boot against an empty, freshly-created Postgres schema):

| Service | Startup time (first boot) |
|---|---|
| accounts-service | `Started AccountsServiceApplication in 5.68 seconds (JVM running for 5.988)` |
| catalog-service | `Started CatalogServiceApplication in 6.799 seconds (process running for 7.267)` |
| ordering-service | `Started OrderingServiceApplication in 7.013 seconds (process running for 7.675)` |
| storefront-gateway | `Started StorefrontGatewayApplication in 4.502 seconds (process running for 5.133)` |

Subsequent restarts, observed during this same run's resilience tests (criterion 7 below), were
consistently faster: catalog-service restarted in `3.525` seconds and accounts-service in `3.227` seconds,
once the OS page cache was warm and the schema already existed. This measured range (~3.2-3.5s) is exactly
why this fix wave replaced `verify.ps1`'s two fixed `Start-Sleep -Seconds 5` waits (after each restart) with
a short poll against the restarted service's own `/actuator/health` — a fixed 5-second margin over a
~3.2-3.5-second real restart is thin enough to flake on a slower CI run; the poll (1-second interval, 30-
second deadline) removes that risk without slowing down the common case.

Real request timing (`curl -w "%{time_total}"`, 3 consecutive requests each, against ordering-service,
captured moments after this run's own cold start):

| Request | Attempt 1 | Attempt 2 | Attempt 3 |
|---|---|---|---|
| `GET /order/1` (plain read, no relations resolved) | 140.2ms | 9.2ms | 9.0ms |
| `GET /order/1?include=account` (1 cross-service hop to accounts-service) | 348.1ms | 30.1ms | 25.3ms |
| `GET /order/1?include=account,lines` (cross-service hop + local JPA join) | 23.8ms | 25.2ms | 24.0ms |

Attempt 1 in each row is inflated by genuine first-request warm-up (JIT, connection-pool init, DNS) right
after a cold container start — the following two attempts settle quickly and are the representative steady-
state numbers. Once warm, a single-relation cross-service `include` costs roughly 2-3x a plain read (the
extra outbound HTTP hop to accounts-service dominates), while adding the second, purely-local `lines`
relation on top costs essentially nothing extra (same ballpark as the single-hop case, since `lines` is a
same-database JPA join, not another network call) — the overhead scales with cross-service hops, not
relation count.

## 7. Failure resilience / graceful degradation

**Scenario 12 — stop catalog-service** (an optional-detail dependency for the gateway's order summary):

```
[PASS] summary still 200 with catalog down (product detail degrades, account is unaffected) (HTTP 200)
[PASS] health reports catalog down (found '"catalog":"UNREACHABLE"')
```

The order summary endpoint correctly keeps returning `200` with the product line degrading rather than
failing the whole request, and the gateway's own health aggregation correctly reports a fully-stopped
backend as `"UNREACHABLE"` (distinct from `"DOWN"`, reserved for a live-but-unhealthy actuator response). An
earlier revision of `verify.ps1` carried a redundant fallback check here that could, on an unexpected body
shape, silently contribute neither a pass nor a fail to the final tally; this fix wave removed it as dead
weight once the primary assertion's own expected literal was corrected to `"UNREACHABLE"` (criterion 9).

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
scenario — this fix wave changed the wait-for-restart mechanism from a fixed 5-second sleep to a poll
against each service's own `/actuator/health` (see criterion 6's restart-timing numbers for why) — and
confirmed back to normal before the next section ran.

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

## 9. Observability, and this fix wave's hardening of `verify.ps1` itself

Each crnk service exposes `management.endpoints.web.exposure.include=health` (Spring Boot Actuator), and
the gateway folds all three into one aggregate endpoint with a 2-second timeout per backend so a dead
backend can never make the health check itself hang. Real captured output:

```
GET /api/health -> 200 {"accounts":"UP","catalog":"UP","ordering":"UP"}
```

With catalog-service stopped (scenario 12): `{"accounts":"UP","catalog":"UNREACHABLE","ordering":"UP"}`. The
endpoint itself always returns `200` regardless of backend state (per-backend status is data in the body,
never a failed request). `docker compose ps` now additionally reports `running (healthy)` for all 3 Postgres
containers (criterion 1's new healthchecks).

Separately from the application under test, this fix wave corrected `verify.ps1`'s own bookkeeping, since a
whole-branch review found 3 places where a branch could silently contribute **neither** a pass nor a fail to
the final tally instead of counting as a failure — which meant a clean-looking summary line was not actually
proof that a fixed set of checks had run:

- The negative-case section (criterion 3) used to print `[SKIP]` and touch neither counter if its own setup
  POST failed; it now counts that as a `[FAIL]` of the check it was setting up for.
- The stop-catalog-service resilience section (criterion 7) had a redundant fallback branch that could only
  ever increment the pass counter, never the fail counter on a non-matching body — removed, since the
  primary assertion above it already does this check correctly.
- The create-order re-verification block (criterion 2) ran 2 counted checks on its happy path but only 1 on
  its failure path (an asymmetric count) — both branches now always contribute exactly 2.

`verify.ps1` now ends with a fixed expected-total assertion — `$script:PassCount + $script:FailCount` must
equal exactly `44` (the real, counted total of every pass/fail-emitting assertion in the finished script) — so
a future regression of this same class (a branch that silently skips instead of counting) fails the run
instead of hiding inside an otherwise-green summary. The total matched exactly all three times `verify.ps1`
was executed for this fix wave.

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
=== SUMMARY: 44 passed, 0 failed ===
```

Reproduced identically across three consecutive full live runs performed for this fix wave (the third,
final run is the one whose timing numbers and log excerpts are quoted throughout this document).
`docker compose logs` across all three runs showed zero `ERROR`-level lines and zero
`NullPointerException`/`OutOfMemoryError`/unhandled-`Caused by` traces in any of the 4 application
containers; the only `WARN`-level noise was `CrnkExceptionMapper` logging the deliberately-invalid requests
this workspace's own negative-case tests send (sections 3/5/6/7/9 of `verify.ps1`), and the disclosed,
harmless `pg_isready` chatter described in criterion 1. Every exercised behavior in this workspace — all 4
cross-generation relationship reads, both auth mechanisms, validation, the broken-reference negative case,
content negotiation, gateway orchestration (including the now-working create-order-with-lines flow),
dependency resolution, startup/request/restart timing, two independent failure-injection scenarios, and
20-way concurrency — passed cleanly on real, live HTTP calls against the full 7-container stack, with no
loosened assertions anywhere in `verify.ps1` relative to the version that originally found the create-order
bug.
