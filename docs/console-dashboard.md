# Live console dashboard

Build and launch from the repository root:

```bash
mvn clean verify
java -cp target/mini-stock-exchange-1.0.0-SNAPSHOT.jar com.misho.exchange.Main 10 100 42
```

Arguments remain duration in seconds, simulator interval in milliseconds, and random seed, and optional selected symbol. Defaults are `10 100 42 AAPL`. The launcher registers AAPL and TSLA and refreshes the display once per second, independently of the simulator interval.

Each row shows last executed price, price change and percentage change from the reference price, best bid/ask, executed volume in shares, trade count, open-order count, and VWAP. Prices, changes, and VWAP are displayed to two decimals; stored values and analytics precision are unchanged. Missing prices and analytics show `--`, including last price before any execution.

In an interactive terminal, the live display uses the ANSI alternate screen buffer, so old frames do not accumulate in normal terminal scrollback when the terminal is resized. Frames redraw in that buffer. Normal completion restores the original terminal and prints one final summary. Closing the dashboard or interrupting the launcher also restores the terminal and cursor. Redirected output, IDE consoles without a Java console, and terminals with `TERM=dumb` receive successive plain-text frames without escape codes. A wide terminal is recommended for the full table.

The header shows elapsed seconds, submitted orders, and RUNNING or COMPLETED status. Normal completion stops the simulator before drawing the final frame. That frame remains visible. Interruption or failure also closes the simulator; failures are reported rather than marked completed.

`MarketSnapshot` is an immutable record captured by `MatchingEngine.getMarketSnapshot()` while holding the engine monitor. `Exchange.getMarketSnapshot(symbol)` routes the request. Each row is internally consistent, but different stock rows and the simulator counter are sampled separately, not as one exchange-wide transaction. Stocks must be registered before concurrent trading begins.

`ConsoleDashboard` formats and prints snapshots; it does not place orders or own background threads. `Main` controls the refresh loop and simulator lifetime. There are no new runtime dependencies.

Tests cover missing values, formatting, terminal escape handling, snapshot stability, concurrent snapshot consistency, and the submission/locale regression fixes.

## Selected stock details and colors

Below the summary, the selected stock shows its latest execution-bearing one-minute candle (open, high, low, close), labelled with the minute start in UTC. It may still be the current, changing candle; it is not a daily OHLC summary. Before any execution, placeholders are displayed.

The depth section shows up to three price levels per side, with aggregated remaining shares. Bids are highest first and asks lowest first; missing levels show `--`. Candle, depth, and summary values for the selected stock come from the same immutable engine snapshot.

Select TSLA with the fourth argument:

```bash
java -cp target/mini-stock-exchange-1.0.0-SNAPSHOT.jar com.misho.exchange.Main 30 100 42 TSLA
```

Interactive terminals use bold headings, green positive changes, and red negative changes. Zero and missing changes remain neutral. Redirected output has neither colors nor screen clearing. Disable colors independently while keeping interactive screen refresh:

```bash
NO_COLOR=1 java -cp target/mini-stock-exchange-1.0.0-SNAPSHOT.jar com.misho.exchange.Main
```

Alternatively pass `-Ddashboard.color=false` before `-cp`. Programmatic callers can control clearing and colors independently using the six-argument `ConsoleDashboard` constructor. Its existing four-argument constructor keeps colors disabled and selects the first stock.

## VS Code terminal refresh

Run the launcher in VS Code's integrated **Terminal**, rather than the Output pane or Debug Console. If Java does not recognize that terminal as interactive, explicitly enable ANSI clearing:

```bash
java -Ddashboard.clear=true -cp target/mini-stock-exchange-1.0.0-SNAPSHOT.jar com.misho.exchange.Main 30 100 42 TSLA
```

Use `-Ddashboard.clear=false` to deliberately print successive frames. Without an override, redirected output stays plain. Forcing clearing intentionally emits ANSI codes even when output is redirected, so reserve it for an ANSI-capable terminal. `NO_COLOR` and `-Ddashboard.color=false` still disable colors independently.

Make the terminal wide enough for the summary table (about 105 columns) and tall enough for the full frame (about 22 rows with two stocks). A smaller viewport may wrap or scroll even when ANSI clearing is enabled. The live alternate buffer keeps old frames out of normal scrollback; existing shell history is preserved. Content may be clipped in a small viewport, so maximize the terminal to see the full layout.

The candle timestamp appears on its own line above `O / H / L / C`. Depth uses compact `BID / SIZE / ASK / SIZE` columns; SIZE is aggregated remaining shares at that price.
