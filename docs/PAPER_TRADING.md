# External-price paper trading

Paper Trading uses virtual money and Alpaca IEX quotes. It never submits brokerage orders. Its accounts, orders, fills, and positions are independent of the Matching Sandbox; existing sandbox balances and orders are preserved.

## Try it locally

Start Spring Boot with `./mvnw spring-boot:run` from the repository root, then run `npm run dev` from `frontend/`. Open http://localhost:5173/#/paper and sign in. Every account starts with $100,000 virtual cash. All ten starter symbols are immediately selectable without admin registration.

Market buys use the ask and sells use the bid. Execution requires the regular session and a valid, uncrossed quote no more than 30 seconds old. Missing or stale data rejects execution rather than substituting the reference price. This is a full-size simulated fill, with no modeled depth, slippage, or fees.

Limit buys reserve cash; limit sells reserve shares. A background worker checks subsequent qualifying quotes approximately every 15 seconds while the server is awake. Orders remain open until filled or cancelled. Checks do not reconstruct missed crossings. Amendments update reservations and require a subsequent quote. Borrowing and short selling are disabled.

The external price chart shows provider historical candles. Fill markers appear only when their times lie inside an available candle; the provider history cutoff can delay their appearance. My Orders also shows execution and source-quote timestamps. Portfolio valuations use external reported prices with timestamps; unavailable marks leave valuation unknown without changing cash or holdings.

## Reliability

Submission requires an Idempotency-Key. The browser retains a pending request in session storage when its outcome is uncertain. Explicitly retrying that same request returns its original result; it does not create another order. There is no automatic submission retry.

Account row locks coordinate reservations, cash, positions, and fills transactionally. Multiple limit workers recheck status under that lock; a unique fill constraint prevents duplicate execution. The database is authoritative and survives restarts.

## API

- Public `GET /api/paper/instruments`
- Authenticated `POST /api/paper/orders` with Idempotency-Key
- `GET /api/paper/orders?page=0&status=OPEN` (status optional: OPEN, FILLED, CANCELLED) and `GET /api/paper/orders/{id}`
- `PATCH /api/paper/orders/{id}` with limitPrice and quantity; `DELETE` cancels
- `GET /api/paper/fills?symbol=AAPL`
- `GET /api/paper/portfolio`

Writes require the existing session and CSRF token. Users can access only their own paper orders. Rate limits apply to trading writes.

Order histories refresh every five seconds while visible. Paper ticket drafts and selected stocks survive navigation between workspace views. Status filtering runs in PostgreSQL before pagination, so open orders are discoverable even when newer terminal orders fill the first page.

### Order history and navigation

Paper Trading, My Orders and Portfolio form the primary account workflow. Matching Sandbox has its own secondary navigation and separate orders and portfolio. Neither history mixes the two accounts.

Histories refresh every five seconds while visible. The summary labels counts from an unfiltered page as “on this page”; use “View all open paper orders” for the server-wide total of your OPEN orders. Sandbox OPEN and PARTIALLY_FILLED filters show their respective server totals. Refresh failures retain the displayed rows with a stale warning.

Paper order rows become labelled cards on narrow screens. Stock and ticket drafts survive navigation between paper views. Session restoration retries read-only checks after a connection failure; trading mutations are never automatically retried.
