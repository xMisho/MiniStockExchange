# Live market simulator

`MarketSimulator` generates local orders through `Exchange`; it does not download
prices or connect to a real market. The matching engine still decides every fill,
execution price, trade record, status, statistic, and candle.

## Run the demo

From the directory containing `pom.xml`, using JDK 17+ and Maven:

```bash
mvn package
java -cp target/mini-stock-exchange-1.0.0-SNAPSHOT.jar com.misho.exchange.Main 10 100 42
```

Arguments are duration in seconds, interval between orders in milliseconds, and
random seed. Defaults are `10`, `100`, and `42`. The launcher registers synthetic
AAPL and TSLA stocks at reference prices 250.00 and 200.00, generates orders, stops,
and prints final statistics. These are example prices, not current market quotes.
There is no continuously refreshing dashboard yet.

## Configuration and controls

With stocks already registered in `exchange`:

```java
try (MarketSimulator simulator = new MarketSimulator(
        exchange, List.of("AAPL", "TSLA"), 100, 10, 50, 42)) {
    MarketSimulator.SimulationStep submitted = simulator.step();
    // Or call start() for periodic background steps, then stop() when finished.
}
```

Import `com.misho.exchange.MarketSimulator` and `java.util.List`. Constructor
arguments after symbols are interval milliseconds, maximum order quantity,
maximum simulator-owned open orders per stock, and seed. All numeric limits must
be positive. Symbols must be registered and unique after normalization.

- `step()` submits exactly one generated order and returns an immutable description.
  It works while stopped and is serialized with scheduled steps.
- `start()` starts one background worker; repeated calls while running do nothing.
- `stop()` waits for an in-flight step to finish and prevents further scheduled
  submissions. It leaves already-resting orders in the books.
- `close()` stops generation and shuts down its executor; it is idempotent. A
  closed simulator cannot restart or step. Use try-with-resources.
- `getSubmittedOrderCount()` counts successful generated submissions, including
  orders that produced no trade.
- `getLastFailure()` exposes a scheduled runtime failure, which stops the run.
  Restart clears that failure. Manual-step failures propagate to the caller.

The scheduler uses a fixed delay after each completed step, not a promise of an
exact number of orders per wall-clock second. Restart preserves the random
sequence and total count. Closed simulator instances cannot be reused.

## Generated orders

Each step randomly selects a configured stock, BUY or SELL, and a quantity from
1 through the configured maximum. The type is approximately 75% LIMIT and 25%
MARKET. Limit prices vary approximately ±1% around the latest trade, falling back
to the reference price before any execution. Prices are rounded HALF_UP to at
least two decimals, retaining extra decimal places used by the anchor price.

ID allocation and submission share the engine's synchronized monitor. Existing
manual IDs, including filled/cancelled IDs, are skipped. Multiple simulators on
the same exchange share that allocator. IDs remain scoped to each stock, and the
existing engine rules reject later manual attempts to reuse a generated ID.

Before adding another LIMIT at its configured open-order cap, the simulator
cancels its oldest still-open order for that stock. It never cancels a manual
order merely to enforce its cap, although manual orders can trade against its
orders normally. Each simulator has its own cap; the total book can include
manual orders and orders from other simulators. Stop/close preserve resting
orders. Trade history, used IDs, and candle history still accumulate in memory.

Register stocks before concurrent trading; the Exchange registry is unchanged.
All simulator state uses synchronized methods, and all submissions/cancellations
continue through the engine's synchronized operations.

## Reproducibility and tests

An equal seed, equal initial books, and the same number of serial `step()` calls
produce equal generated-order decisions and execution results in the absence of
other activity. Wall-clock timestamps and minute buckets are not seeded.
Scheduled runs can submit different counts due to scheduling; manual trading or
additional simulators can also change the results.

```bash
mvn -Dtest=MarketSimulatorTest test
mvn clean test
```

Tests cover configuration, same/different seeds, generated prices/types/quantities,
bounded books, manual-ID coexistence, concurrent simulator IDs, small prices,
stop/restart, shutdown, visible failures, and integration with existing statistics.
