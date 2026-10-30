# MiniStockExchange

A concurrent Java multi-stock exchange simulator with price-time priority, limit and market orders, order amendments, trading analytics, and a live console dashboard.

Java 17+ · Maven · Spring Boot · JUnit · Concurrent matching · 12 V2 features

## Core matching behavior

The highest bid and lowest ask match when their prices cross. FIFO queues preserve insertion order at the same price, and each execution uses the resting order’s price. Partial fills leave the remaining limit-order quantity in the book. Cancellation removes an open order and cleans up empty price levels.

Concurrent operations are serialized by each matching engine’s `synchronized` methods. FIFO priority for concurrent submissions follows acquisition of that monitor, not order creation timestamps.

## V2 features

| # | Feature | Behavior |
| --- | --- | --- |
| 1 | Order amendment | Change an open order’s price or remaining quantity. Same-price reductions preserve FIFO priority; increases or price changes move the order to the back of its price queue. Crossing amendments match immediately. |
| 2 | Multi-stock support | Each registered symbol has its own stock metadata, matching engine, and order book. Order IDs are unique for the lifetime of each engine, including after filling or cancellation. |
| 3 | Last traded price | Updated only by actual executions; `null` before the first trade. The reference price is kept separately. |
| 4 | Market orders | Execute immediately against available opposite-side limit orders, best price first. Any unfilled remainder is cancelled instead of resting in the book. |
| 5 | Order status | Track `OPEN`, `PARTIALLY_FILLED`, `FILLED`, and `CANCELLED`, along with remaining and filled quantities. |
| 6 | Fast order lookup | A hash-based ID index finds open orders without scanning every price level. Removing an order still scans its selected price queue. |
| 7 | Market depth | Immutable snapshots of aggregated remaining quantity at each price, with bids highest first and asks lowest first. |
| 8 | Trading analytics / VWAP | Trade count, total executed volume, average trade size, and volume-weighted average execution price. |
| 9 | One-minute OHLC | Execution-based open, high, low, and close prices in UTC minute buckets, exposed as immutable snapshots. |
| 10 | Price change statistics | Signed absolute and percentage changes from each stock’s fixed reference price. |
| 11 | Live market simulator | Seeded generation of BUY/SELL and LIMIT/MARKET orders, configurable speed and quantity, lifecycle controls, and limits on its own open orders. |
| 12 | Live console dashboard | Refreshing stock summaries, optional colors, selected-stock OHLC, top three bid/ask levels, and consistent snapshots per stock. |

![Live console dashboard showing simulated AAPL and TSLA prices, trading statistics, one-minute OHLC, and market depth](assets/dashboard.png)

## Technical concepts

`TreeMap`, `NavigableMap`, `ArrayDeque`, `HashMap`, `HashSet`, `BigDecimal`, `synchronized`, `volatile`, scheduled executors, immutable records, and JUnit concurrency tests.

## Architecture

| Class | Responsibility |
| --- | --- |
| `Order` | Validated limit/market order, amendments, fill quantities, and lifecycle status |
| `OrderSide` | BUY / SELL enum |
| `OrderBook` | Price levels, FIFO queues, cancellation, and book statistics |
| `Trade` | Immutable execution record |
| `MatchingEngine` | Submission, matching, ID uniqueness, history, analytics, and concurrency boundary |
| `Exchange` / `Stock` | Symbol routing and stock metadata / price statistics |
| `OrderType` / `OrderStatus` | Limit/market type and lifecycle enums |
| `OhlcCandle` | Immutable one-minute execution-price snapshot |
| `MarketSimulator` | Seeded generation, scheduling, and limits on its own open orders |
| `MarketSnapshot` | Immutable per-stock statistics captured under the engine monitor |
| `ConsoleDashboard` | Live console rendering with plain-output fallback |
| `Main` | Simulator and dashboard launcher |
| `MiniStockExchangeApplication` | Spring Boot web application entry point |
| `HealthController` | Basic HTTP health endpoint |
| `stock.StockEntity` / `StockRepository` | Database stock mapping and JPA queries |
| `stock.StockService` / `StockController` | Stock registration, validation, and REST endpoints |
| `stock.StockResponse` | Stock metadata returned by the API |
| `user.UserEntity` / `UserRepository` | User database mapping and uniqueness queries |
| `user.UserService` / `UserController` | Validated account registration and safe responses |
| `user.PasswordConfiguration` | Salted PBKDF2 password encoder |
| `user.DatabaseUserDetailsService` | Loads credentials and roles for Spring Security |
| `auth.SecurityConfiguration` / `AuthController` | Session login/logout, CSRF, access rules, and current-user profile |
| `trading.OrderEntity` / `OrderRepository` | Owned order snapshots, FIFO sequencing, and optimistic locking |
| `trading.TradeEntity` / `TradeRepository` | Persistent execution records and history queries |
| `trading.TradingService` | Service-owned transactions, locked live-engine matching, ownership, and recovery |
| `trading.MarketLoader` / `MarketRegistry` | Validated restoration and publication of committed engines |

```text
MiniStockExchange/
├── README.md
├── .gitignore
├── .env.example
├── pom.xml
├── mvnw / mvnw.cmd
├── .mvn/wrapper/
├── assets/
│   └── dashboard.png
└── src/
    ├── main/java/com/misho/exchange/
    │   ├── ConsoleDashboard.java
    │   ├── Exchange.java
    │   ├── HealthController.java
    │   ├── Main.java
    │   ├── MarketSimulator.java
    │   ├── MarketSnapshot.java
    │   ├── MatchingEngine.java
    │   ├── MiniStockExchangeApplication.java
    │   ├── OhlcCandle.java
    │   ├── Order.java
    │   ├── OrderBook.java
    │   ├── OrderSide.java
    │   ├── OrderStatus.java
    │   ├── OrderType.java
    │   ├── Stock.java
    │   └── Trade.java
    ├── main/resources/
    │   ├── application.properties
    │   └── db/migration/
    └── test/java/com/misho/exchange/
        ├── ConsoleDashboardTest.java
        ├── SubmissionValidationTest.java
        ├── MarketSimulatorTest.java
        ├── MatchingEngineConcurrencyTest.java
        ├── MiniStockExchangeApplicationTests.java
        ├── PostgresqlSetupTest.java
        ├── MiniStockExchangeTest.java
        ├── OhlcTest.java
        └── PriceChangeTest.java
```

## V3 foundation: Spring Boot

V3 development starts with Spring Boot 4.1.2 and Spring Web, integrated into the existing project. The matching engine and console launcher remain available. PostgreSQL connectivity, JPA/Hibernate, and Flyway are configured. Stock metadata persistence, registration, and read endpoints are now implemented. User registration is now implemented with hashed passwords. Session login, logout, current-user lookup, CSRF protection, and admin-only stock registration are now implemented. Persistent matching and startup recovery are implemented through `TradingService`. Authenticated trading HTTP endpoints and public market snapshot, depth, paginated trade-history, and one-minute candle endpoints are implemented; the web frontend is not implemented yet.

Set up PostgreSQL as described below, then start the web application from the repository root:

```bash
./mvnw spring-boot:run
```

Open `http://localhost:8080/api/health`. A running server returns:

```json
{"status":"UP"}
```

Stop it with Ctrl+C. This endpoint checks that the web application is responding; it does not continuously probe the database or expose the exchange. Application startup requires a working database connection and successful migrations. The root URL has no page yet. Starting the web server does not automatically start the market simulator.

The generated Maven wrapper downloads its configured Maven distribution on first use. On Windows, use `mvnw.cmd` instead of `./mvnw`.

## Persisted stocks and REST endpoints

The `exchange.stocks` table contains the approved metadata fields:

| Column | PostgreSQL type | Rules |
| --- | --- | --- |
| `symbol` | `VARCHAR(20)` | Primary key; required and nonblank |
| `company_name` | `VARCHAR(255)` | Required and nonblank |
| `reference_price` | `NUMERIC(19,4)` | Required and strictly positive |

The Java service trims names and normalizes symbols with `Locale.ROOT`. Prices that would lose decimal precision or exceed the column range are rejected instead of silently rounded. Duplicate registration is rejected; creation uses JPA `persist`, so a concurrent duplicate cannot silently update an existing row.

After starting Spring Boot:

```bash
curl http://localhost:8080/api/stocks
curl http://localhost:8080/api/stocks/AAPL
```

- `POST /api/stocks` validates and saves metadata, returns `201 Created` with a Location header, `400` for invalid data, or `409 Conflict` for an existing symbol. Concurrent duplicates cannot overwrite an existing row.
- `GET /api/stocks` returns metadata sorted by symbol, or `[]` if none is saved.
- `GET /api/stocks/{symbol}` accepts case-insensitive symbols, returns `404` for an unknown symbol, and `400` for an invalid symbol.
- Each response contains only `symbol`, `companyName`, and `referencePrice`.

Example record:

```json
{"symbol":"AAPL","companyName":"Apple Inc.","referencePrice":250.0000}
```

The table starts empty on a fresh installation; migrations do not automatically seed market prices. Register stocks through the API after following the admin login steps below. These POST examples assume `COOKIE_JAR` and `CSRF_TOKEN` belong to that logged-in admin session:

```bash
curl -i -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST http://localhost:8080/api/stocks \
  -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","companyName":"Apple Inc.","referencePrice":345.29}'

curl -i -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST http://localhost:8080/api/stocks \
  -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"TSLA","companyName":"Tesla, Inc.","referencePrice":401.70}'
```

These values were registered in the original local development database using timestamped USD quotes observed on October 28, 2026: [Apple, $345.29 at 11:43 AM EDT](https://stockanalysis.com/stocks/aapl/) and [Tesla, $401.70 at 11:24 AM EDT](https://stockanalysis.com/stocks/tsla/). They are fixed reference-price snapshots, not guaranteed current quotes or a live feed; obtain fresh prices for a new registration. Reposting an existing symbol returns `409` and leaves the saved record unchanged.

Registration is also available through `StockService.registerStock(symbol, companyName, referencePrice)` for application code. Integration tests use their own records in the separate test database and remove only those records afterward. Stock registration now requires an authenticated `ADMIN` session and a valid CSRF token.

`StockEntity` lives in `com.misho.exchange.stock`, separate from the existing engine `Stock` class. Saved metadata survives application restarts. The persistent trading service restores registered markets at startup and loads newly registered stocks on their first trading operation. Last price and book state are reconstructed from durable orders and trades. The standalone console `Exchange` remains independent.

## User registration

Flyway V3 creates `exchange.users`, and V4 adds the `role` column without changing the applied V3 migration:

| Column | PostgreSQL type | Rules |
| --- | --- | --- |
| `id` | `BIGINT` | Generated primary key |
| `username` | `VARCHAR(50)` | Required; unique; 3–50 lowercase letters, digits, or underscores |
| `email` | `VARCHAR(254)` | Required; unique; validated email format |
| `password_hash` | `VARCHAR(255)` | Required; stores the salted password hash only |
| `created_at` | `TIMESTAMPTZ` | Required; UTC registration timestamp |
| `role` | `VARCHAR(10)` | Required; `USER` by default, or explicitly assigned `ADMIN` |

Usernames and emails are trimmed and normalized to lowercase using `Locale.ROOT`; duplicates are therefore case-insensitive. Passwords are preserved exactly, including spaces, and must contain 15–128 characters and not be entirely blank. Email format validation does not verify ownership of an address.

With Spring Boot running, first obtain an anonymous session and CSRF token, then register an example account. These terminal examples use `curl` and Python 3 to extract the JSON token:

```bash
COOKIE_JAR=$(mktemp)
CSRF_TOKEN=$(curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" http://localhost:8080/api/auth/csrf | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
```

Register using that cookie jar and token:

```bash
curl -i -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST http://localhost:8080/api/users \
  -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"username":"example_user","email":"example_user@example.test","password":"ExampleOnlyPassword123!"}'
```

Use a password of your own for an actual account; the example above is demonstration data. `POST /api/users` returns:

- `201 Created` with only `id`, `username`, `email`, `createdAt`, and `role`.
- `400 Bad Request` for invalid fields or malformed JSON.
- `409 Conflict` when the username or email is already registered. Database uniqueness constraints also handle concurrent duplicate registrations.

Passwords use Spring Security Crypto’s PBKDF2-HMAC-SHA256 encoder with 600,000 iterations, a random 16-byte salt, and a 256-bit derived key. Stored hashes include the `{pbkdf2-sha256}` algorithm identifier. Identical passwords receive different salts. The encoder is separate from the registration logic, and its `matches` method is available for the future login feature. See [Spring’s encoder API](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/password/Pbkdf2PasswordEncoder.html) and [OWASP password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

Raw passwords and stored hashes are excluded from registration responses. The request type redacts its `toString()` and does not serialize its password; it is not returned as a response. The service validates registration data even when called directly instead of over HTTP.

Registration creates a `USER` account and does not automatically log it in. The request has no role field: submitting `role: "ADMIN"` cannot grant privileges. No user or administrator is created automatically. There is no public users list, email verification, password reset, or registration rate limiting yet. Integration tests use temporary accounts in the separate test database and remove only their own records.

## Login and access control

Authentication uses Spring Security’s session-based form login. There are no JWTs. The database stores users and password hashes; the server stores the logged-in session in memory.

| Endpoint | Access / format |
| --- | --- |
| `GET /api/health` | Public |
| `GET /api/stocks` and `/api/stocks/{symbol}` | Public |
| `GET /api/auth/csrf` | Public; returns the token and header name and creates a session when needed |
| `POST /api/users` | Public registration; JSON body and valid CSRF token required |
| `POST /api/auth/login` | Public; form-encoded `username` and `password`, plus CSRF token |
| `GET /api/auth/me` | Authenticated user; safe profile including role |
| `POST /api/auth/logout` | CSRF-protected logout; returns `204` |
| `POST /api/stocks` | Authenticated `ADMIN` plus CSRF token |

After obtaining `COOKIE_JAR` and `CSRF_TOKEN` as shown in registration, log in with the example account:

```bash
curl -i -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
  --data-urlencode 'username=example_user' \
  --data-urlencode 'password=ExampleOnlyPassword123!' \
  http://localhost:8080/api/auth/login
```

Login accepts a username, not an email address. It normalizes username case and surrounding whitespace but preserves the password exactly. The response is safe user metadata on success, or a generic `401` for incorrect credentials. Form fields are used here so Spring Security’s standard authentication filter handles password checking, session fixation protection, and CSRF token rotation.

Login rotates the session ID and invalidates the previous CSRF token. Keep the updated cookie jar and fetch a new token before any further write:

```bash
CSRF_TOKEN=$(curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" http://localhost:8080/api/auth/csrf | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl -i -b "$COOKIE_JAR" http://localhost:8080/api/auth/me
```

Anonymous access to protected endpoints returns `401`, and a logged-in `USER` attempting stock registration receives `403`. A missing, invalid, or stale CSRF token also returns `403`, even on public write endpoints. Public reads do not require a token. Requests use the `X-CSRF-TOKEN` header returned by the token endpoint.

Log out with the current session and token:

```bash
curl -i -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -H "X-CSRF-TOKEN: $CSRF_TOKEN" \
  -X POST http://localhost:8080/api/auth/logout
rm "$COOKIE_JAR"
```

Logout invalidates the session, clears its authentication and CSRF token, and expires the session cookie. It cannot be triggered by GET. Fetch a fresh anonymous token before registering or logging in again.

### Assigning an administrator

The first registered user is not automatically an administrator, and there is no public role-management endpoint. An operator with authorized database access must explicitly promote an existing account. Connect to the development database as its owner and deliberately run:

```sql
UPDATE exchange.users SET role = 'ADMIN' WHERE username = 'example_user';
```

Then log out and log in again before registering stocks. Authorities are captured at login; manually changing a database role does not refresh an existing session. Use explicit operator control for demotion and terminate affected sessions when immediate revocation is needed; there is no runtime role-management feature yet.

### Session behavior

The session cookie is HttpOnly and SameSite=Lax, and sessions expire after 30 minutes of inactivity. URL-based session tracking and HTTP Basic authentication are disabled. Sessions are not persisted across server restarts, so users must log in again afterward; their database accounts remain saved. The frontend is expected to use the session cookie and CSRF-token endpoint.

Local development uses HTTP. For HTTPS deployment, set `SESSION_COOKIE_SECURE=true` to require secure cookies. Keep the application local until deployment controls, login throttling, email verification, and the frontend integration are addressed. Cross-origin frontend access is not configured in this step.

## Orders, trades, and persistent matching

The database model and `TradingService` are implemented, including locked live-engine matching, failure recovery, and startup restoration. Authenticated trading HTTP endpoints are implemented for submission, owned history, lookup, amendment, and cancellation. The standalone console simulator remains an independent in-memory demo and does not write orders or trades to PostgreSQL.

Flyway V5 creates `exchange.orders`, `exchange.trades`, and an order-priority sequence. Stock links use `stock_symbol`, referencing the existing `stocks.symbol` primary key; there is no separate numeric stock ID. Database order IDs are global across stocks and are intended to become the engine order IDs in the later integration.

### Orders

| Column | Meaning |
| --- | --- |
| `id` | Generated `BIGINT` order ID |
| `user_id` | Required foreign key to the owner in `users` |
| `stock_symbol` | Required foreign key to `stocks` |
| `side` / `order_type` | BUY or SELL; LIMIT or MARKET |
| `limit_price` | Positive `NUMERIC(19,4)` for LIMIT; null for MARKET |
| `original_quantity` | Positive quantity originally submitted; never amended |
| `remaining_quantity` / `filled_quantity` | Current nonnegative quantities |
| `status` | OPEN, PARTIALLY_FILLED, FILLED, or CANCELLED |
| `priority_sequence` | Unique positive FIFO priority allocated from a database sequence |
| `created_at` / `updated_at` | Original creation and latest state-update timestamps in UTC |
| `version` | JPA optimistic-lock version that rejects stale database updates |

An OPEN order has positive remaining quantity and no fills. PARTIALLY_FILLED has positive remaining and filled quantities. FILLED has zero remaining and positive filled quantity. CANCELLED retains its unfilled remaining quantity, matching the existing engine behavior.

There is deliberately no constraint requiring `original_quantity = remaining_quantity + filled_quantity`: amendments can increase or reduce remaining quantity while the original quantity stays historical. Filled quantity cannot decrease through the entity's state-update method, and terminal orders cannot be reactivated there. Prices are validated for exact four-decimal storage rather than silently rounded.

FIFO restoration must use `priority_sequence`, not `created_at` or order ID. Same-price reductions preserve that sequence; increased remaining quantity or a changed price requires a fresh sequence. Amendments preserve original creation time. Sequence values may have gaps; they are ordering tokens, not order counts. `TradingService` serializes priority allocation with matching operations so allocation order reflects accepted queue order.

`OrderRepository` exposes owner history, all previously used IDs for a stock, and active LIMIT orders in priority order. It also identifies unfinished MARKET orders separately: those must never be restored as resting orders. An unfinished persisted MARKET is a condition the recovery layer must reject or reconcile, rather than silently ignore.

### Trades

| Column | Meaning |
| --- | --- |
| `id` | Generated `BIGINT` execution sequence ID |
| `stock_symbol` | Stock that traded |
| `buy_order_id` / `sell_order_id` | Required references to two distinct orders |
| `execution_price` | Positive `NUMERIC(19,4)` actual execution price |
| `quantity` | Positive executed share quantity |
| `executed_at` | Original execution timestamp in UTC |

Two additional database-generated labels, `buy_side` and `sell_side`, are fixed to BUY and SELL. Composite foreign keys use those labels with the stock symbol to prevent wrong-side or cross-stock order references, including direct SQL inserts. They are integrity helpers, not application-supplied trade fields.

JPA trade fields are not updatable. Histories are loaded by trade ID to preserve execution ordering even when timestamps tie or a clock moves backward. Trade IDs must be allocated in actual execution order by the future coordinator. `TradeRepository` also provides internal queries for trades involving a particular user's orders. These queries are not exposed as public endpoints.

Foreign keys retain history: users, stocks, and referenced orders cannot be deleted while dependent orders/trades exist. Indexes support owner history, restoration ordering, stock trade history, and trade/order lookups. SQL does not compute cross-row fill totals. `TradingService` persists order states and new trades together, and recovery verifies that saved trade quantities agree with each order’s filled quantity.

### Transaction and engine consistency

`TradingService` is the only supported coordinator for persisted trading. It supports submission, cancellation, amendment, owned order history, market snapshots, trade history, and candle reads as Java service methods. Trading controllers derive the actor ID from the authenticated session user; client-supplied owner IDs are never used for authorization.

Each mutation follows this sequence:

1. Hold the service’s synchronization monitor through preparation, matching, transaction completion, and publication.
2. Open a service-owned database transaction and acquire a PostgreSQL advisory transaction lock, with a five-second lock-wait limit.
3. Reuse the service-owned engine, loading it from PostgreSQL only on a cache miss. Persist a new order ID when submitting, then apply the command while keeping the service monitor held.
4. Collect only the command's target and counterparties from newly created trades. Fetch those managed order rows, update their quantities/status/price/FIFO priority, and persist only new trades. Flush and commit without copying or scanning historical engine orders/trades.
5. Publish the successful engine while still holding the monitor. Readers acquire the same monitor, so they cannot observe temporary uncommitted engine mutations.

A SQL rollback does not undo Java memory. On a runtime failure or an error, the service replaces the mutated engine by reconstructing the market from the actual committed database state before releasing its monitor. If recovery fails, the engine is removed and the market is blocked. Errors are rethrown after reconciliation is attempted. Process termination or resource exhaustion can prevent in-process recovery; startup recovery is still required. A transaction error does not prove PostgreSQL rolled back: commit may already have succeeded.

The invariant is: **outside the service monitor, every available engine reflects committed database state**. Engine references are private to the internal registry and are never returned by `TradingService`; snapshot/history results are immutable. All persisted trading must pass through this service. The engine's own monitor alone does not protect the longer database transaction.

If reconciliation succeeds, the recovered engine is published and the original operation still reports a failure. **Inspect durable order history before retrying a failed operation**, especially after a commit/publication error. The service does not automatically retry commands and does not yet offer request-idempotency keys. A committed operation can therefore exist even when its caller received an exception; The HTTP boundary reports this as `503` with `TRADING_OUTCOME_UNCERTAIN` and `retrySafe: false`.

If reconciliation fails, that market’s cached engine is removed and its trading/snapshot operations remain blocked until explicit `recoverMarket(symbol)` succeeds or the application restarts successfully. Durable order-history reads remain available when the database is accessible. Unknown symbols are ordinary input errors and do not permanently disable a subsequently registered stock.

The service rejects calls made inside an existing database transaction: an outer rollback must not undo records after the engine has already been made available. Database optimistic versions remain a guard against stale updates. A checked integer addition also prevents cumulative fill counts from silently wrapping after repeated amendments.

### Startup and restart recovery

The application-ready listener restores all registered stocks from PostgreSQL before completing application startup. It loads active LIMIT orders by saved FIFO priority, preserves original/remaining/filled quantities and creation timestamps, reserves IDs from terminal as well as active orders, and replays existing trade history by execution ID for last price, volume, VWAP, and OHLC. Restoration does not call matching or create new trades.

Recovery rejects unfinished MARKET orders, crossed resting books, invalid saved trade references, and fill totals that disagree with trade history. It does not silently cancel corrupt records, invent executions, or discard history. Startup fails if saved market state cannot be restored; operators must investigate the data before serving persisted trading.

The Step 2.5 test starts a fresh application context, verifies the restored book and analytics, then submits another order to prove the saved FIFO priority actually controls its fills. Other tests inject failures after live-engine matching, during trade persistence, immediately before commit, after commit, and during publication/recovery. They also check blocked readers during commit, rollback of amendments/cancellations, ownership checks, admin overrides, and concurrent commands.

### Current integration boundaries

This is intentionally a **single-active-backend** design. Do not run multiple backend writers against one live market: the advisory transaction lock serializes participating database transactions, but it does not keep independently cached engines in different application instances synchronized. Direct repository/SQL changes to order/trade records bypass the coordinator and are unsupported during trading, apart from deliberate maintenance followed by recovery.

Normal writes fetch only metadata and affected orders and persist the command's delta. Full history reconstruction is reserved for startup, cache misses, explicit recovery, and failures. Engine history still consumes growing memory; full-history read APIs and recovery still scale with history size. Matching a large order can still touch many counterparties, and queue removal can scan a price level. This is not a claim of constant-time matching or production throughput. Sessions, the standalone `Exchange`, and the console simulator remain separate concerns; web trading routes through `TradingService`.

Normal users can view and manage their own orders through the service. Admins can inspect, amend, or cancel another user’s orders as an explicit operator override; ownership remains unchanged. The HTTP endpoints enforce these ownership and admin rules. Request idempotency is not implemented.

### Reproducible persistence benchmark

At 20,000 historical orders and 10,000 trades, local median resting-order submission dropped from **176.4 ms to 1.2 ms**, and executing market submission dropped from **179.1 ms to 6.9 ms** after replacing per-command history reconstruction with delta persistence.

The opt-in benchmark uses the isolated test database and deletes only its own fixtures. Each size contains 100, 1,000, or 10,000 historical trades and twice as many filled orders. It times resting submission, priority-losing amendment, cancellation, and an executing market submission. Each case uses five warmup cycles and ten measured cycles; setup, initial recovery, and fixture cleanup are outside the timings. Run separately from other test/database workloads:

```bash
./mvnw -Dtest=TradingBenchmarkTest -Dtrading.benchmark=true test
```

Local comparison on 2026-10-29 (same fixture and ten-sample procedure; baseline code at `5dee98c`). Median latency in milliseconds:

| Historical orders / trades | Resting submit before → after | Amendment before → after | Cancellation before → after | Executing market submit before → after |
| --- | --- | --- | --- | --- |
| 200 / 100 | 8.459 → 2.358 | 8.773 → 3.843 | 8.474 → 3.452 | 10.039 → 5.324 |
| 2,000 / 1,000 | 24.089 → 1.172 | 24.489 → 1.951 | 21.309 → 1.927 | 25.585 → 2.763 |
| 20,000 / 10,000 | 176.371 → 1.159 | 166.812 → 4.271 | 154.125 → 3.909 | 179.097 → 6.939 |

At the largest size, mean latency changed from 176.914 → 1.179 ms for resting submit, 167.114 → 4.291 ms for amendment, 155.406 → 3.918 ms for cancellation, and 180.811 → 7.107 ms for executing market submit. These short sequential runs demonstrate the removed history replay cost; differences between optimized fixture sizes also reflect warmup/cache/machine effects, so they do not establish a scaling curve or throughput SLA.

It prints median and mean milliseconds for each operation. Ordinary regression runs skip the benchmark unless explicitly enabled. Results are local diagnostic measurements, not a production capacity guarantee; logging, JIT compilation, database cache, and machine load affect timing.

## Market snapshot API

`GET /api/stocks/{symbol}/snapshot` is public and returns one stock's consistent summary through the synchronized `TradingService`. Symbols are normalized to uppercase. The dedicated `MarketSnapshotResponse` DTO keeps the JSON contract separate from the internal snapshot; depth and candles are omitted from this response. Depth is available from its dedicated endpoint below; candles are available from their dedicated endpoint below.

```bash
curl http://localhost:8080/api/stocks/AAPL/snapshot
```

Illustrative response after executions (values depend on this application's persisted orders and trades, not a live external market feed):

```json
{
  "symbol": "AAPL",
  "lastTradedPrice": 101.00,
  "priceChange": 1.00,
  "priceChangePercent": 1.0000,
  "bestBidPrice": 99.00,
  "bestAskPrice": 101.00,
  "spread": 2.00,
  "volume": 2,
  "tradeCount": 1,
  "openOrderCount": 2,
  "vwap": 101.0000
}
```

This example assumes a reference price of 100.00. Price change is last execution minus reference price; percentage change uses that reference as the denominator. Volume counts executed shares once per trade. Open-order count counts resting orders, not shares. Spread is best ask minus best bid and is null if either quote is absent. Before any executions, last price, price change, percentage change, and VWAP are null; counters are zero for an empty market. Resting orders can populate quotes without creating an execution. Decimal precision is preserved for clients to format.

Returns `404` for an unregistered symbol, `400` for an invalid symbol, and `503` when market data cannot be recovered or the market is blocked. Read errors do not expose internal database/recovery details. This endpoint does not expose order owners, individual orders, depth, candles, or credentials. Requests require neither a login nor a CSRF token; trading mutations retain their existing protection.

## Market depth API

`GET /api/stocks/{symbol}/depth?levels=3` is public and returns aggregated remaining quantities at each price. It uses one immutable snapshot obtained under the `TradingService` monitor, so the bid and ask sides come from the same market state.

```bash
curl 'http://localhost:8080/api/stocks/AAPL/depth?levels=3'
```

Illustrative response:

```json
{
  "symbol": "AAPL",
  "bids": [{"price": 99.00, "quantity": 12}],
  "asks": [{"price": 101.00, "quantity": 8}]
}
```

Bids are highest price first; asks are lowest price first. `quantity` is a `long` sum of remaining shares at that price, not the number of orders or their original quantities. Numerically equal prices share a price level. The limit applies independently to each side and counts price levels, not orders. It defaults to 3 and must be an integer between 1 and 100; invalid values return `400`. Empty sides return empty arrays. Partial fills, cancellations, amendments, and recovery are reflected in subsequent reads.

Symbols are normalized to uppercase. Unknown symbols return `404`, invalid symbols/parameters return `400`, and unavailable markets return `503` with a safe error message. No login or CSRF token is required. The response exposes no individual order IDs or owners. Responses are bounded, although the existing internal snapshot still aggregates the complete open book before selecting the requested levels.

## Public trade history API

`GET /api/stocks/{symbol}/trades?page=0&size=25` reads committed executions directly from PostgreSQL through `TradingService`, without copying the engine's history or requiring the live market to be available. It remains readable when engine recovery is blocked, provided the database is accessible. Public reads require neither login nor CSRF.

```bash
curl 'http://localhost:8080/api/stocks/AAPL/trades?page=0&size=25'
```

Illustrative response:

```json
{
  "content": [
    {"tradeId": 42, "symbol": "AAPL", "executionPrice": 101.00, "quantity": 2, "executedAt": "2026-10-30T09:00:00Z"}
  ],
  "page": 0,
  "size": 25,
  "totalElements": 1,
  "totalPages": 1
}
```

Results sort by trade ID descending, not execution timestamp. This gives deterministic ordering even for tied or backward-moving timestamps. Only `tradeId`, `symbol`, `executionPrice`, `quantity`, and `executedAt` are exposed; user IDs and buy/sell order IDs stay private. Prices retain stored precision, and execution times are UTC instants.

Page numbers start at zero and default to 0. Size defaults to 25 and must be 1–100. Invalid parameters return `400`, including page offsets exceeding the persistence provider's supported integer offset. Symbols are normalized to uppercase; unknown stocks return `404`. Empty histories and pages beyond the last page return `200` with empty `content` and pagination metadata. Offset-based pages can shift when new trades arrive between requests; clients should refresh rather than treat successive pages as a fixed historical snapshot. Database unavailability returns a safe `503` error.

## One-minute candle API

`GET /api/stocks/{symbol}/candles` aggregates committed PostgreSQL trades into UTC minute buckets. It does not create a candles table, synthesize idle candles, or read from the live engine. Public reads remain available while a market is blocked if PostgreSQL is accessible.

```bash
curl 'http://localhost:8080/api/stocks/AAPL/candles?limit=100'
curl 'http://localhost:8080/api/stocks/AAPL/candles?from=2026-10-30T09:00:00Z&to=2026-10-30T10:00:00Z&limit=100'
```

Illustrative response:

```json
[
  {"startTime":"2026-10-30T09:00:00Z","open":100.00,"high":103.00,"low":99.00,"close":102.00,"volume":20,"tradeCount":4}
]
```

Each response candle contains only `startTime`, `open`, `high`, `low`, `close`, `volume`, and `tradeCount`. Open is the first execution ordered by `(executed_at ASC, id ASC)`; close is the last ordered by `(executed_at DESC, id DESC)`. High/low are maximum/minimum execution prices. Volume sums executed quantities once per trade, and trade count counts executions. Volume and count use `long` values. Prices retain database precision. This timestamp-based ordering intentionally differs from the trade-history API's ID-based ordering and the engine's execution-sequence candle replay if timestamps move backward.

`from` and `to` are optional ISO-8601 instants with UTC (`Z`) or an explicit offset. Either bound may be supplied alone. Execution filtering is **from inclusive, to exclusive**, before aggregation; a partially covered minute therefore represents only trades within the requested range. Buckets still start at the UTC minute boundary. If both bounds are supplied, `from` must precede `to`. Supported bounds are within calendar years 1–9999 UTC. PostgreSQL stores microsecond timestamps; finer request boundaries are rounded upward for comparisons so inclusivity/exclusivity is preserved for stored execution times.

The API selects the **latest N execution-bearing buckets within the filter**, then returns them oldest to newest. Limit defaults to 100 and must be an integer from 1–1000. Minutes without executions are omitted. The current minute's candle can change as trades arrive, and a candle is not marked final. No matching trades returns `200 []`; unknown stock returns `404`; invalid symbol, timestamps, range, or limit returns `400`; database unavailability returns a safe `503`. No login or CSRF token is required.

Flyway migration V6 adds `(stock_symbol, executed_at, id)` to support stock/time-range access. Existing migrations are unchanged. Returned candles are bounded, but aggregation still processes the matching execution history; materialized candles and tighter query-cost controls are deferred until measured workloads justify them.

## Trading API

All trading routes require a session login. POST, PATCH, and DELETE also require the CSRF token obtained from `/api/auth/csrf`; refresh that token after login. Requests use JSON, and responses contain order data rather than JPA entities or credentials.

| Method | Route | Success |
| --- | --- | --- |
| POST | `/api/stocks/{symbol}/orders` | `201` with the resulting order and a `Location` header |
| GET | `/api/orders/me` | `200` with a page of the current user's order history, including terminal orders |
| GET | `/api/orders/{id}` | `200` with an owned order; admins may inspect any order |
| PATCH | `/api/orders/{id}` | `204` after amendment and any resulting matching |
| DELETE | `/api/orders/{id}` | `204` after cancellation |

History supports `GET /api/orders/me?page=0&size=25&status=OPEN`. Page numbers start at zero, size defaults to 25 and must be 1–100, and status is optional (`OPEN`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`). Results are ordered by descending order ID, with a stable tie-free ordering; concurrent new submissions can still shift offset-based pages. Invalid pagination or status values return `400`.

```json
{"content":[],"page":0,"size":25,"totalElements":0,"totalPages":0}
```

Order responses expose identity, owner, symbol, side/type, price, quantities, status, and timestamps. Internal FIFO priority sequences and persistence versions are not exposed. Inspect all relevant history pages when checking an uncertain submission.

Submit a limit order:

```json
{"side":"BUY","type":"LIMIT","limitPrice":100.00,"quantity":5}
```

Submit a market order (omit `limitPrice` or use null):

```json
{"side":"BUY","type":"MARKET","quantity":5}
```

Amend an open limit order:

```json
{"newPrice":101.00,"newRemainingQuantity":3}
```

The database generates order IDs. Ownership comes from the login session. Ordinary users can inspect, amend, and cancel only their own orders; admins may manage another user's order without changing its owner. `/api/orders/me` always lists only the caller's orders, including for admins. Amendment preserves the existing FIFO rules and may execute trades immediately. Market orders execute against available liquidity and cancel any unfilled remainder.

Error responses: `400` for invalid requests, `401` for missing authentication, `403` for ownership violations or invalid/missing CSRF, `404` for unknown stocks/orders, and `409` for amending or cancelling a terminal order. Invalid requests do not intentionally modify the book. State changes are checked again by the service if another command completes between HTTP checks and mutation.

A `503` mutation response with `code: TRADING_OUTCOME_UNCERTAIN` and `retrySafe: false` means the database may already have committed. **Check `/api/orders/me` and the relevant order before retrying.** No automatic retries or idempotency keys are provided, and a lost HTTP response can also conceal a successful operation. If durable history cannot be read, wait for recovery rather than blindly resubmitting. An unavailable market also returns `503` without exposing internal error details.

## PostgreSQL setup

PostgreSQL 17 must be running locally before starting Spring Boot or running the Spring integration tests. There is no H2 fallback. Flyway creates the `exchange` schema plus `stocks`, `users`, `orders`, and `trades` tables. Stock metadata and users are integrated with APIs; orders and trades persist when operations use `TradingService`, while the standalone console still trades only in memory. The matching engine still runs in memory.

For a new local setup, connect as a PostgreSQL administrator:

```bash
psql -d postgres
```

Create a dedicated login and separate development/test databases. The `\password` command prompts for the password rather than placing it in SQL or shell history:

```sql
CREATE ROLE mini_stock_exchange LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password mini_stock_exchange
CREATE DATABASE mini_stock_exchange OWNER mini_stock_exchange;
CREATE DATABASE mini_stock_exchange_test OWNER mini_stock_exchange;
```

Exit psql with `\q`. Copy the configuration template, restrict access, and edit `.env` to enter the password you set:

```bash
cp .env.example .env
chmod 600 .env
```

`DB_URL` selects the development database, `DB_TEST_URL` the test database, and `DB_USERNAME` / `DB_PASSWORD` supply the credentials. Keep the test database separate and use a name ending in `_test`. Environment variables can override the file. The application reads `.env` from the working directory, so launch from the repository root or supply the variables explicitly.

`.env` and other local `.env.*` files are ignored by Git; `.env.example` contains placeholders only. Do not put passwords in committed properties files. On the original development machine these databases and a private `.env` have already been created, so do not recreate them or overwrite that file.

Flyway runs `src/main/resources/db/migration/V1__initialize_exchange_schema.sql` on first startup and records it in `public.flyway_schema_history`. Later starts validate previously applied migrations and run only new ones. V1 creates the `exchange` schema, V2 creates `stocks`, V3 creates `users`, and V4 adds user roles, and V5 creates the order/trade persistence model. Later tables will use new versioned migrations. Do not edit migrations after they have been applied.

Hibernate uses `ddl-auto=validate`; it will not silently create or update tables. Open Session in View is disabled. The Flyway clean operation is disabled as well.

On the original Homebrew-based macOS setup, the installed PostgreSQL server can be started and stopped without changing login-service settings:

```bash
/opt/homebrew/opt/postgresql@17/bin/pg_ctl -D /opt/homebrew/var/postgresql@17 -l /opt/homebrew/var/postgresql@17/server.log -w start
/opt/homebrew/opt/postgresql@17/bin/pg_ctl -D /opt/homebrew/var/postgresql@17 -m fast -w stop
```

Only stop it when no other local applications need that server. On other installations, use that installation’s service controls. Docker Compose and Testcontainers are deferred; this step uses the existing local PostgreSQL installation.

## Build and run

Install JDK 17 or newer. Clone the project, complete the PostgreSQL setup above, then verify with the Maven wrapper:

```bash
git clone https://github.com/xMisho/MiniStockExchange.git
cd MiniStockExchange
./mvnw clean verify
```

Run the dashboard from the repository root in VS Code’s integrated **Terminal** or another ANSI-capable terminal:

```bash
java -Ddashboard.clear=true -cp target/mini-stock-exchange-1.0.0-SNAPSHOT.jar com.misho.exchange.Main 30 100 42 TSLA
```

The positional arguments are optional:

| Argument | Meaning | Default |
| --- | --- | --- |
| `durationSeconds` | How long the simulation runs | `10` |
| `intervalMillis` | Delay between generated orders | `100` |
| `seed` | Seed for random order generation | `42` |
| `selectedSymbol` | Stock shown in the OHLC and depth sections | `AAPL` |

The launcher registers simulated AAPL and TSLA stocks with reference prices of 250.00 and 200.00. These are example prices, not live market quotes. The plain JAR has no executable manifest, so launch the console by class name as shown above. After changing code, rebuild with `./mvnw package`.

Packaging also creates a separate executable Spring Boot JAR:

```bash
java -jar target/mini-stock-exchange-1.0.0-SNAPSHOT-boot.jar
```

### Dashboard controls and terminal behavior

The dashboard refreshes once per second, independently of the simulator interval. The summary shows last price, price change, percentage change, best bid/ask, executed volume in shares, trade count, open-order count, and VWAP. Prices and VWAP display two decimals without changing stored precision. Missing values show `--`.

The selected stock’s latest execution-bearing one-minute candle appears below the summary, with its minute-start timestamp in UTC. The depth display shows the top three prices on each side, with SIZE representing aggregated remaining shares.

Live mode uses the alternate terminal screen to keep old frames out of normal scrollback. Normal scrolling returns when the simulation stops. Completion restores the original terminal and prints one final summary; **Ctrl+C** also restores the terminal and cursor. Keep the terminal wide and tall enough for the full frame, around 105 columns and 22 rows for the two-stock demo.

- `-Ddashboard.clear=true` explicitly enables live mode if terminal detection fails.
- `-Ddashboard.clear=false` prints successive frames for scrolling or logging.
- Without an override, interactive terminals use live mode; redirected output and unrecognized consoles receive plain frames.
- `NO_COLOR=1` or `-Ddashboard.color=false` disables green/red changes and bold headings independently of live mode.

For example, retain every update as plain scrolling output:

```bash
java -Ddashboard.clear=false -cp target/mini-stock-exchange-1.0.0-SNAPSHOT.jar com.misho.exchange.Main 30 100 42 AAPL
```

Only force live mode in a terminal that supports ANSI sequences; the override intentionally emits them even if output is redirected.

## API examples

Import the classes from `com.misho.exchange`, plus `java.math.BigDecimal`:

```java
Exchange exchange = new Exchange();
exchange.registerStock(new Stock("AAPL", "Apple Inc.", new BigDecimal("100")));

exchange.submitOrder("AAPL", new Order(1, OrderSide.SELL, new BigDecimal("101"), 5));
exchange.submitOrder("AAPL", new Order(2, OrderSide.BUY, new BigDecimal("105"), 2));

System.out.println(exchange.getLastTradedPrice("AAPL"));
System.out.println(exchange.getTotalTradedVolume("AAPL"));
System.out.println(exchange.getOpenOrder("AAPL", 1).getRemainingQuantity());
```

The output is `101`, `2`, and `3`: the trade executes at the resting SELL price, and three shares remain on that order.

Submit a market order using a `null` limit price:

```java
exchange.submitOrder("AAPL", new Order(3, OrderSide.BUY, OrderType.MARKET, null, 1));
```

Amend or cancel the remaining SELL order:

```java
boolean amended = exchange.amendOrder("AAPL", 1, new BigDecimal("102"), 2);
boolean cancelled = exchange.cancelOrder("AAPL", 1);
```

Amendment quantity means **new remaining quantity**, not original quantity. Original quantity and creation timestamp stay unchanged. Amendments of absent, filled, or cancelled orders return `false`. For an existing order, non-positive quantities or null/non-positive prices are rejected. Same-price, same-quantity amendments are successful no-ops.

Read a consistent view of one stock:

```java
MarketSnapshot snapshot = exchange.getMarketSnapshot("AAPL");
System.out.println(snapshot.lastTradedPrice());
System.out.println(snapshot.buyDepth());
System.out.println(snapshot.latestCandle());
```

## Analytics and market data

- **VWAP:** the sum of execution price × executed quantity, divided by total executed quantity, rounded to four decimals with HALF_UP. Average trade size rounds to two decimals. Both are `null` before any trade; executed volume counts shares, not monetary turnover.
- **Price change:** last traded price minus reference price. Percentage change is that difference × 100 / reference price, rounded to four decimals with HALF_UP. Both are `null` before trading and signed afterward. This is not a daily change unless the reference price was chosen as the previous day’s close; there is no daily reset.
- **OHLC:** open is the first execution in a minute, high/low are its maximum/minimum execution prices, and close is its latest execution price. Minute buckets include their start and exclude their end. Idle minutes create no candles. The latest candle may still be changing or may belong to an earlier minute. Reference prices and non-trading actions do not create candles.
- **Snapshots:** depth maps, candle history, and trade-history lists are unmodifiable snapshots. Previously returned candles do not change after later executions. Best prices are `null` when that side is empty, and spread is `null` unless both sides exist.

The engine and exchange expose getters for depth, trade history, VWAP, average trade size, candles, and book statistics. `Exchange.getMarketSnapshot(symbol)` captures the summary, latest candle, and depth together under that stock’s engine monitor. Different stock rows and the simulator counter are sampled separately, not as one exchange-wide transaction.

## Simulator behavior

`MarketSimulator` generates local orders through `Exchange`; it does not download prices. Each step selects a configured symbol, side, and quantity, with approximately 75% LIMIT and 25% MARKET orders. Limit prices vary roughly ±1% around the latest execution, falling back to the reference price before trading.

With stocks already registered, construct a simulator using interval milliseconds, maximum quantity, maximum simulator-owned open orders per stock, and seed:

```java
try (MarketSimulator simulator = new MarketSimulator(
        exchange, java.util.List.of("AAPL"), 100, 10, 50, 42)) {
    MarketSimulator.SimulationStep step = simulator.step();
}
```

`step()` submits one order manually. `start()` enables periodic background steps; `stop()` waits for any in-flight step and prevents further scheduled submissions. `close()` stops and shuts down the executor. Stop/close leave resting orders in the book. Restart preserves the random sequence and submission count; a closed instance cannot restart. Scheduled failures stop generation and are available through `getLastFailure()`.

Generated IDs skip all IDs previously used by that stock’s engine, including manual, filled, and cancelled orders. At its open-order cap, the simulator cancels its oldest still-open order before another LIMIT submission. This cap applies only to that simulator’s own orders, not the entire book.

Equal seeds and the same number of serial manual steps reproduce order decisions with equal initial books and no other activity. Scheduled counts depend on timing; timestamps and minute buckets are not seeded.

## Testing

Run all tests or one suite:

```bash
mvn test
mvn -Dtest=ConsoleDashboardTest test
```

Spring integration tests use the `test` profile and the dedicated `DB_TEST_URL` database. They verify real PostgreSQL connectivity, Flyway migration history, a Hibernate query, the HTTP health endpoint, stock constraints, registration/read endpoints, malformed payload rejection, concurrent duplicate rejection, and stock persistence after closing and restarting the application. User registration tests cover validation, case-insensitive duplicates, concurrent username/email collisions, salted hashing and password matching, safe responses, and request redaction. Authentication tests cover anonymous/USER/ADMIN access, failed login, session ID rotation, CSRF validation and rotation, privilege-escalation attempts, logout, and replay of invalidated sessions. Core matching and console tests remain independent of the database.

The test suite covers matching, FIFO priority, partial fills, cancellation, duplicate IDs, amendment priority, multi-stock isolation, market orders, lifecycle status, indexed lookup, depth, analytics, OHLC boundaries, price changes, simulator lifecycle, symbol normalization, and dashboard rendering and terminal cleanup.

Concurrency tests exercise matching and snapshot reads while orders are submitted. The original matching stress test expects **2,000 trades, volume 2,000, and zero open orders**. Worker failures propagate to the test. OHLC tests use an injected clock for exact boundaries without sleeping.

Surefire writes results to `target/surefire-reports/`. Concurrency tests validate the exercised workloads; they are not a proof for every possible thread schedule.

## Scope and limitations

This is a multi-stock, in-memory learning project. It has persistent stock metadata, database migrations, and stock registration/read endpoints, plus user registration and session authentication, with persistent matching and engine recovery, but no web frontend, fees, account balances, or real exchange connectivity. The health endpoint reports web-server responsiveness; stock endpoints return saved metadata. Durable order and trade history grows over time; engines reconstruct analytics and candle history from persisted executions. The standalone console remains an in-memory demo.

Register stocks before concurrent trading begins. Use one engine per initially empty book, and interact through the engine during concurrent activity. Do not share submitted order instances across engines, mutate submitted orders, or access their books directly. Returned open orders are live objects, not immutable snapshots; use market snapshots for consistent concurrent statistics. Separate getter calls are independent observations.

## What this project taught me

Price priority and time priority are separate concerns: sorted maps select a price, while each deque selects the oldest order there. Matching must update quantities, record executions, and clean the book as one operation. A single engine monitor keeps that sequence easy to reason about.

V2 extended that foundation with amendments, separate stock markets, market-order execution, lifecycle tracking, indexed lookup, and immutable analytics snapshots. The simulator and dashboard build on those APIs while keeping order generation, matching, and terminal presentation separate.

## Project history

Extracted from [Java-Projects](https://github.com/xMisho/Java-Projects), preserving commits that changed this project’s Java sources, including original authors, timestamps, and commit messages. Commit hashes changed because unrelated files and directory prefixes were removed. The Maven migration is a subsequent commit. The original monorepo remains available.

The layout follows [Maven’s standard directory conventions](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html); test dependencies are managed by the Spring Boot parent.
