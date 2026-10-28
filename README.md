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

V3 development starts with Spring Boot 4.1.2 and Spring Web, integrated into the existing project. The matching engine and console launcher remain available. PostgreSQL connectivity, JPA/Hibernate, and Flyway are configured. Stock metadata persistence, registration, and read endpoints are now implemented. User accounts, order/trade persistence, trading endpoints, and the web frontend are not implemented yet.

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

The table starts empty on a fresh installation; migrations do not automatically seed market prices. Register stocks through the API:

```bash
curl -i -X POST http://localhost:8080/api/stocks \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","companyName":"Apple Inc.","referencePrice":345.29}'

curl -i -X POST http://localhost:8080/api/stocks \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"TSLA","companyName":"Tesla, Inc.","referencePrice":401.70}'
```

These values were registered in the original local development database using timestamped USD quotes observed on October 28, 2026: [Apple, $345.29 at 11:43 AM EDT](https://stockanalysis.com/stocks/aapl/) and [Tesla, $401.70 at 11:24 AM EDT](https://stockanalysis.com/stocks/tsla/). They are fixed reference-price snapshots, not guaranteed current quotes or a live feed; obtain fresh prices for a new registration. Reposting an existing symbol returns `409` and leaves the saved record unchanged.

Registration is also available through `StockService.registerStock(symbol, companyName, referencePrice)` for application code. Integration tests use their own records in the separate test database and remove only those records afterward. This local-development write endpoint has no authentication yet; access control will be added with the users feature.

`StockEntity` lives in `com.misho.exchange.stock`, separate from the existing engine `Stock` class. Saved metadata survives application restarts, but saving a stock does not register it in a live `Exchange`, create its matching engine, or persist its last price or order book. That integration is a later step.

## PostgreSQL setup

PostgreSQL 17 must be running locally before starting Spring Boot or running the Spring integration tests. There is no H2 fallback. Flyway creates the `exchange` schema and `stocks` table. Stock metadata can be stored and retrieved through JPA; users, orders, and trades are not stored yet. The matching engine still runs in memory.

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

Flyway runs `src/main/resources/db/migration/V1__initialize_exchange_schema.sql` on first startup and records it in `public.flyway_schema_history`. Later starts validate previously applied migrations and run only new ones. V1 creates the `exchange` schema and V2 creates its `stocks` table. Later tables will use new versioned migrations. Do not edit migrations after they have been applied.

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

Spring integration tests use the `test` profile and the dedicated `DB_TEST_URL` database. They verify real PostgreSQL connectivity, Flyway migration history, a Hibernate query, the HTTP health endpoint, stock constraints, registration/read endpoints, malformed payload rejection, concurrent duplicate rejection, and stock persistence after closing and restarting the application. Core matching and console tests remain independent of the database.

The test suite covers matching, FIFO priority, partial fills, cancellation, duplicate IDs, amendment priority, multi-stock isolation, market orders, lifecycle status, indexed lookup, depth, analytics, OHLC boundaries, price changes, simulator lifecycle, symbol normalization, and dashboard rendering and terminal cleanup.

Concurrency tests exercise matching and snapshot reads while orders are submitted. The original matching stress test expects **2,000 trades, volume 2,000, and zero open orders**. Worker failures propagate to the test. OHLC tests use an injected clock for exact boundaries without sleeping.

Surefire writes results to `target/surefire-reports/`. Concurrency tests validate the exercised workloads; they are not a proof for every possible thread schedule.

## Scope and limitations

This is a multi-stock, in-memory learning project. It has persistent stock metadata, database migrations, and stock registration/read endpoints, but no persistent user/order/trade models, web frontend, trading REST API, fees, account balances, or real exchange connectivity. The health endpoint reports web-server responsiveness; stock endpoints return saved metadata. Trade history, used IDs, and candle history accumulate for the lifetime of the engine.

Register stocks before concurrent trading begins. Use one engine per initially empty book, and interact through the engine during concurrent activity. Do not share submitted order instances across engines, mutate submitted orders, or access their books directly. Returned open orders are live objects, not immutable snapshots; use market snapshots for consistent concurrent statistics. Separate getter calls are independent observations.

## What this project taught me

Price priority and time priority are separate concerns: sorted maps select a price, while each deque selects the oldest order there. Matching must update quantities, record executions, and clean the book as one operation. A single engine monitor keeps that sequence easy to reason about.

V2 extended that foundation with amendments, separate stock markets, market-order execution, lifecycle tracking, indexed lookup, and immutable analytics snapshots. The simulator and dashboard build on those APIs while keeping order generation, matching, and terminal presentation separate.

## Project history

Extracted from [Java-Projects](https://github.com/xMisho/Java-Projects), preserving commits that changed this project’s Java sources, including original authors, timestamps, and commit messages. Commit hashes changed because unrelated files and directory prefixes were removed. The Maven migration is a subsequent commit. The original monorepo remains available.

The layout follows [Maven’s standard directory conventions](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html); test dependencies are managed by the Spring Boot parent.
