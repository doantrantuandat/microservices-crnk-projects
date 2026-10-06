# crnk-framework-lts interop workspace

Four independent Maven projects that validate the published library
`io.github.doantrantuandat:*:4.0.0-lts.2` (a fork/LTS line of
[crnk-framework](https://github.com/crnk-project/crnk-framework)) exactly the way a real external Maven
Central consumer would: no multi-module reactor, no shared internal library, no local `mvn install` of the
library itself — every project resolves `crnk-*` straight from Central via `crnk-bom`. The four projects
span three Spring Boot generations (2, 3, 4) and both of the library's Jackson lines (classic Jackson 2 and
the newer `tools.jackson` 3 line used by the Boot 4 artifacts), wired together into one Docker Compose stack
so the library's cross-version wire compatibility, crnk-data-jpa relationship handling, and crnk-client
behavior can be exercised with real HTTP calls against a live stack rather than asserted from unit tests in
isolation.

## Architecture

```
                         +-------------------------------+
                         |      storefront-gateway        |
                         |   Boot 4.1.1, plain REST        |
                         |  (no crnk server dependency -   |
                         |   crnk-client-jackson3 + plain   |
                         |   Spring RestClient only)        |
                         +----+-----------+-----------+----+
                              |           |           |
              crnk-client     |           |           |   crnk-client
           (orders/summary)   |           |           |   (raw account read,
                               |           |           |    plain RestClient)
                               v           v           v
   +----------------------+   |   +------------------+   +----------------------+
   |   accounts-service    |<--+   |  catalog-service  |   |  ordering-service     |
   |   Boot 2.3.12, javax  |       |  Boot 3.5.16      |-->|  Boot 4.1.1, jakarta  |
   |   crnk-data-jpa-legacy|       |  jakarta, Jackson2 |   |  Jackson 3            |
   |   Account{id,name,    |       |  crnk-data-jpa     |   |  crnk-data-jpa-       |
   |    email,plan}        |       |  Product{sku,name, |   |    jackson3           |
   +-----------^-----------+       |   price,owner->Acct}|   |  Order{orderNumber,  |
               |                   +---------^----------+   |   account->Account}  |
               |  REVERSE skip-gen           |               |  OrderLine{qty,      |
               |  Account.orders -> Order    | forward        |   product->Product, |
               |  (accounts queries ordering, | (adjacent-gen) |   order->Order}     |
               |   skipping catalog entirely) | OrderLine      +-----------+---------+
               |                              | .product ->               |
               +------------------------------+ Product                   |
               |                                                           |
               +--------------------- forward (skip-gen) ------------------+
                 Order.account -> Account (ordering queries accounts,
                 skipping catalog entirely - the mirror image of the
                 REVERSE relation above)
```

Four relationship shapes are exercised, matching crnk's own "generation" terminology for how far apart two
Boot major versions are in this chain (accounts=gen Boot2, catalog=gen Boot3, ordering=gen Boot4):

| # | Direction | Relation | Generations spanned |
|---|---|---|---|
| 1 | forward, adjacent | `Product.owner -> Account` | Boot3 -> Boot2 |
| 2 | forward, adjacent | `OrderLine.product -> Product` | Boot4 -> Boot3 |
| 3 | forward, skip-gen | `Order.account -> Account` | Boot4 -> Boot2 (bypasses Boot3) |
| 4 | **reverse**, skip-gen | `Account.orders -> Order` | Boot2 -> Boot4 (bypasses Boot3) |

`storefront-gateway` is a 5th, deliberately different project: no crnk server module at all, consuming all
three backends purely as an external client (`crnk-client-jackson3` for two endpoints, a plain
`org.springframework.web.client.RestClient` for the other two), proving the JSON:API wire format needs no
crnk-specific client to read.

## Ports

| Service | Host port | Container port | Notes |
|---|---|---|---|
| storefront-gateway | 28080 | 8080 | HTTP Basic on `POST /api/orders` only; everything else open |
| accounts-service | 28081 | 8080 | MockAuth (`X-Mock-Role: admin`) on `Account` create/delete |
| catalog-service | 28082 | 8080 | MockAuth on `Product` create/delete |
| ordering-service | 28083 | 8080 | MockAuth on `Order` create/delete (`OrderLine` ungated) |
| accounts-db (Postgres 16) | 25432 | 5432 | |
| catalog-db (Postgres 16) | 25433 | 5432 | |
| ordering-db (Postgres 16) | 25434 | 5432 | |

## Running locally without Docker

Each service's `application.properties` already bakes in a `localhost:<port>` default for every
cross-service URL, so you can run any subset of services directly off the host (e.g. in 4 separate
terminals) with no environment variables at all, as long as the ports line up with the table above:

- `accounts-service`: `ordering.service.url=${ORDERING_SERVICE_URL:http://localhost:28083}`
- `catalog-service`: `accounts.service.url=${ACCOUNTS_SERVICE_URL:http://localhost:28081}`
- `ordering-service`: `accounts.service.url=${ACCOUNTS_SERVICE_URL:http://localhost:28081}`,
  `catalog.service.url=${CATALOG_SERVICE_URL:http://localhost:28082}`
- `storefront-gateway`: all three of the above (`ACCOUNTS_SERVICE_URL`, `CATALOG_SERVICE_URL`,
  `ORDERING_SERVICE_URL`)

Each also needs a real Postgres reachable at `${DB_HOST:localhost}:${DB_PORT:5432}` with a
`crnk`/`crnk`-owned database matching its name (`accountsdb`/`catalogdb`/`orderingdb`) — the three
`*-db` Postgres containers in `docker-compose.yml` can be brought up alone (`docker compose up -d
accounts-db catalog-db ordering-db`) and everything else run as a plain `mvn spring-boot:run` or the
packaged jar on the host, pointed at `localhost:25432`/`25433`/`25434`.

## Docker Compose quickstart

```powershell
cd D:\Projects\microservices-crnk-projects
mvn -f accounts-service/pom.xml clean package -DskipTests
mvn -f catalog-service/pom.xml clean package -DskipTests
mvn -f ordering-service/pom.xml clean package -DskipTests
mvn -f storefront-gateway/pom.xml clean package -DskipTests
docker compose up --build -d
# wait for all 7 containers to settle (docker compose ps), then:
.\verify.ps1
docker compose down -v
```

Seed data (idempotent across restarts — each service's own `ApplicationRunner` checks before inserting):
`Account` 1/2/3 (Alice Nguyen/pro, Bob Tran/free, Carol Pham/free), `Product` 1/2/3 (SKU-001 Wireless
Mouse/19.99, SKU-002 Mechanical Keyboard/89.50, SKU-003 USB-C Hub/34.00, all `accountId` 1), `Order` 1
(ORD-1001, `accountId` 1) with `OrderLine` 1 (`orderId` 1, `productId` 1, qty 2).

## Further reading

- [`EVALUATION.md`](./EVALUATION.md) — the 10-criterion findings writeup, backed by the real `verify.ps1`
  run captured in this repository (39 passed / 5 failed, with the failures traced to one precise, genuine
  finding plus one benign by-design redundant check — see that document for the full breakdown).
- [`verify.ps1`](./verify.ps1) — the end-to-end validation script itself; run it against the live stack to
  reproduce every result cited in `EVALUATION.md`.
