# Price change statistics

Price change compares the most recent actual execution price with the stock's
fixed reference price. It is not a daily change unless that reference was chosen
as the previous day's close. There is no automatic daily reset.

```text
price change = last traded price - reference price
percentage change = (last traded price - reference price) × 100 / reference price
```

Both are signed: a price drop produces negative values. `getPriceChange()` keeps
exact decimal arithmetic. `getPriceChangePercent()` rounds once, to four decimal
places using HALF_UP. The percentage value is expressed as a percent, not a ratio:
reference 100 and last trade 110 produce change 10 and percentage 10.0000 (10%).

## API

On an existing exchange with the symbol registered:

```java
BigDecimal change = exchange.getPriceChange("AAPL");
BigDecimal percent = exchange.getPriceChangePercent("AAPL");
```

Import `java.math.BigDecimal`. The same getters are available on `Stock` without
a symbol argument. Before an actual trade both return null. After an execution at
the reference price they return zero, rather than null. Unknown, blank, and null
symbols are rejected by the Exchange getters.

Limit, market, and amendment-triggered executions already update the stock's last
traded price, so all three affect these calculations. Non-trading submissions,
cancellations, and amendments do not. Calculations do not modify the reference
price, matching engine, or order book.

Each getter captures the existing volatile last-traded price once. Separate getter
calls are independent observations, not a combined snapshot during concurrent
trading. Stocks should be registered before concurrent trading begins, following
the existing Exchange registration model.

## Tests

```bash
mvn -Dtest=PriceChangeTest test
mvn clean test
```
