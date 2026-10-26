# One-minute OHLC candles

Each stock has its own candle history, built only from actual executions.

- **Open:** first execution price in that minute.
- **High / Low:** highest / lowest execution prices in that minute.
- **Close:** latest execution price in that minute.

Minute buckets use the trade timestamp in UTC epoch milliseconds. The interval
includes its start and excludes its end: a trade at `09:00:59.999` belongs to
`09:00`, while one at `09:01:00.000` starts the `09:01` candle. Prices retain their
`BigDecimal` precision; there is no additional rounding.

Minutes with no executions do not produce candles. Submissions, cancellations,
non-crossing amendments, and reference prices do not create or change candles.
Trades caused by limit orders, market orders, or crossing amendments all use the
same recording path under the matching engine monitor.

## API

```java
NavigableMap<Long, OhlcCandle> candles = exchange.getOhlcCandles("AAPL");
OhlcCandle latest = exchange.getLatestOhlcCandle("AAPL");
```

Import `java.util.NavigableMap` and `com.misho.exchange.OhlcCandle`; `exchange`
above is an existing `Exchange` with AAPL registered. The engine exposes the same
methods without the symbol argument. Unknown symbols are rejected.

The map is chronological, keyed by `getMinuteStartTime()`. Each candle exposes
`getOpen()`, `getHigh()`, `getLow()`, and `getClose()`. Both the map and candle
objects are immutable snapshots: later trades cannot change previously returned
values. Read again to obtain an updated current-minute candle.

Before any trade, the map is empty and the latest candle is `null`. After idle
minutes, the latest getter still returns the most recent minute that traded;
it does not fabricate a current candle or imply that the returned candle is a
completed minute. History is retained in memory for the engine's lifetime.

## Verification

`OhlcTest` uses an injected clock to test exact minute boundaries without sleeping.
The ordinary public constructors still use the system UTC clock. The original
public `Trade` constructor remains available. The existing concurrent matching
test also reads candle snapshots while orders execute.

```bash
mvn -Dtest=OhlcTest test
mvn clean test
```
