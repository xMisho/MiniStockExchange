# MiniStockExchange

A Java limit-order-book simulation with price-time priority, partial fills, trade history, order cancellation, and serialized concurrent access.

Java 17+ · Maven · JUnit 5 · No production dependencies

## Features

- Highest bid and lowest ask are matched whenever their prices cross.
- FIFO queues preserve arrival order among orders at the same price.
- Partial fills leave the unfilled quantity in the book.
- Each match records an immutable trade at the resting order’s limit price.
- Cancellation removes an open order and cleans up empty price levels.
- Order IDs remain reserved after filling or cancellation.
- Book statistics expose best bid, best ask, spread, and open-order counts.
- Trade statistics expose execution count and total executed quantity.

## Technical concepts

`TreeMap`, `NavigableMap`, `ArrayDeque`, `BigDecimal`, `HashSet`, `ReentrantLock`, `CountDownLatch`, and immutable snapshots.

## Architecture

| Class | Responsibility |
| --- | --- |
| `Order` | Validated limit order and remaining quantity |
| `OrderSide` | BUY / SELL enum |
| `OrderBook` | Price levels, FIFO queues, cancellation, and book statistics |
| `Trade` | Immutable execution record |
| `MatchingEngine` | Submission, matching, ID uniqueness, history, and concurrency boundary |

```text
MiniStockExchange/
├── README.md
├── .gitignore
├── pom.xml
└── src/
    ├── main/java/com/misho/exchange/
    │   ├── MatchingEngine.java
    │   ├── Order.java
    │   ├── OrderBook.java
    │   ├── OrderSide.java
    │   └── Trade.java
    └── test/java/com/misho/exchange/
        └── MiniStockExchangeTest.java
```

## Build and run

Install JDK 17 or newer and Maven 3.9+. Clone the repository and run its tests:

```bash
git clone https://github.com/xMisho/MiniStockExchange.git
cd MiniStockExchange
mvn test
```

Build and verify the library JAR:

```bash
mvn clean verify
```

The artifact is written to `target/mini-stock-exchange-1.0.0-SNAPSHOT.jar`. These classes are a library and test suite, so the JAR has no application entry point. Run the JUnit class from an IDE, or run a single suite with:

```bash
mvn -Dtest=MiniStockExchangeTest test
```

## Testing

Nine JUnit tests cover trade recording, cancellation, market statistics, duplicate IDs, FIFO priority, partial fills, resting-order execution prices in both directions, cancellation at a non-best price, and concurrent matching.

The stress test starts two buyers, two sellers, and one statistics reader together. Each submitting thread creates 1,000 quantity-one orders. Worker failures are propagated to the test. Expected final result: **2,000 trades, volume 2,000, zero open orders**.

Surefire writes detailed results to `target/surefire-reports/`. Concurrency tests validate invariants for the exercised workload; they are not a proof for every possible thread schedule.

## Example behavior

Use these classes from the `com.misho.exchange` package (and import `java.math.BigDecimal` for the exchange example):

```java
MatchingEngine engine = new MatchingEngine(new OrderBook());
engine.submitOrder(new Order(1, OrderSide.SELL, new BigDecimal("100"), 4));
engine.submitOrder(new Order(2, OrderSide.BUY, new BigDecimal("105"), 10));

Trade trade = engine.getTradeHistory().get(0);
System.out.println(trade.getExecutionPrice()); // 100: resting SELL price
System.out.println(trade.getQuantity());       // 4
System.out.println(engine.getOpenOrderCount()); // 1: BUY has 6 remaining
```

## Scope and limitations

This is a single-instrument, in-memory learning project. It has no persistence, market orders, network API, fees, account balances, or real exchange connectivity. Cancellation scans the book rather than using an ID index.

Use one engine per initially empty book. Concurrent callers must interact through the engine; do not mutate submitted orders or read/change its book directly. Separate statistics calls each acquire the lock independently and are not a combined snapshot. IDs and trade history are retained for the lifetime of that engine instance.

## What this project taught me

Price priority and time priority are separate concerns: the sorted maps select a price, while each deque selects the oldest order there. Matching must update quantities, record executions, and clean the book as one operation. A single engine lock keeps that sequence easy to reason about.

## Project history

Extracted from [Java-Projects](https://github.com/xMisho/Java-Projects), preserving commits that changed this project’s Java sources, including original authors, timestamps, and commit messages. Commit hashes changed because unrelated files and directory prefixes were removed. The Maven migration is a subsequent commit. The original monorepo remains available.

The layout follows [Maven’s standard directory conventions](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html); tests use [JUnit 5](https://docs.junit.org/5.11.4/user-guide/).
