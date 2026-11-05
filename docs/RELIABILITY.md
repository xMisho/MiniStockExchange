# Trading reliability and operations

## Idempotent submissions

`POST /api/stocks/{symbol}/orders` accepts `Idempotency-Key`: 16–128 ASCII letters, digits, `_` or `-`. Keys are scoped to the authenticated user. The browser generates a UUID for each new submission, persists the key and exact payload in per-user sessionStorage before sending, and offers **Retry same submission safely** after an uncertain outcome, including a page reload in the same tab. It never retries automatically. A different order must use a new key. Clients omitting the header retain the legacy non-idempotent behavior; use a key for every submission.

The database stores the normalized payload fingerprint and original submission response in `exchange.order_requests` in the same transaction as the order, fills, quantities and audit actions. Same user/key/payload returns the original HTTP 201 body and Location even after later fills or amendments. Same user/key with a different payload returns 409 `IDEMPOTENCY_CONFLICT`. Different users have separate key spaces. Validation/transaction rollback does not consume a key. Commit uncertainty is reconciled by explicit replay, never by assuming a rollback. A saved result can be replayed even while its engine is blocked; writer fencing still applies. Keys are retained for the lifetime of their orders; deleting order history also removes its keys, so do not delete records then replay old requests.

Amendment, cancellation and demo allocation are not idempotent-key APIs. Their uncertain outcomes still require history/portfolio verification. SessionStorage recovery is specific to that browser tab/account; other clients must retain their own keys. Clearing browser storage loses that recovery aid but does not remove database keys.

## Paper account risk controls

Each account starts with $100,000 virtual cash and no shares. New BUY orders require cash, SELL orders require owned shares; borrowing and new shorts are disabled. LIMIT buys reserve `limit price × remaining quantity`, LIMIT sells reserve remaining shares. Open order rows are the durable reservations; fills, amendments and cancellations update them in the trading transaction. Prices better than a BUY limit release the difference. MARKET buys check the current full-book executable cost while holding the same lock; missing liquidity is cancelled and reserves nothing. MARKET sells conservatively require holdings for the entire requested quantity, even if some would not execute.

Cash includes committed local buy/sell cash flows and admin allocations. Availability subtracts all open reservations across symbols. Amendment checks exclude that order's old reservation, then check its requested replacement. Admin amendment uses the owner's funds, not the admin's. The existing global service monitor and PostgreSQL advisory transaction lock serialize checks and writes across accounts/symbols. Rollback and restart reconstruction use committed rows, so there is no in-memory reservation balance to drift. Fill counterparties are checked too: pre-existing undercollateralized orders cannot execute new unbacked trades.

`GET /api/stocks/{symbol}/balance` returns the caller's cash, reserved/available cash and owned/reserved/available shares. `GET /api/portfolio` also includes reserved/available cash. A failed check returns 409 with `INSUFFICIENT_CASH`, `INSUFFICIENT_SHARES` or `INSUFFICIENT_COLLATERAL`. The ticket displays available amounts; the server remains authoritative. Existing negative accounts/history are preserved; cancel or fund their orders before further trading. `verdex.risk.enabled=true` is the default; the legacy test suite disables it only for fixtures intentionally demonstrating short positions, while dedicated enabled integration tests cover enforcement.

### Admin demo allocation

`POST /api/admin/paper-allocations` accepts `{ "userId": 123, "symbol": "AAPL", "quantity": 10 }`. Only ADMIN can call it, with CSRF. The Local Exchange admin allocation form defaults to the admin's own user ID; specify another recipient ID if needed. This purchases shares from a simulated treasury using the recipient's available virtual cash at the registered reference price. `exchange.paper_allocations` records recipient, symbol, quantity, price, operator and time. It creates no exchange trade or external brokerage action. New users need an allocation or a purchase from a funded seller before selling.

## Metrics and probes

Spring Boot Actuator/Micrometer instruments HTTP latency as `http.server.requests`, plus JVM and Hikari metrics. Custom meters:

- `verdex.login.failures`: rejected credential checks.
- `verdex.rate_limit.{login,registration,trading,external,reads}`: throttled requests.
- `verdex.trading.duration{operation=matching|command|transaction}`: engine matching, full command, and coordinated transaction duration.
- `verdex.database.failures`: failed coordinated database transactions (including commit errors).
- `verdex.recovery.attempts` and `verdex.recovery.failures`: startup and explicit/error recovery.
- `verdex.idempotency.replays`: stored submission results returned.

No username, order ID, request key or symbol is used as a metric tag. `GET /actuator/metrics`, `/actuator/metrics/{name}` and `/actuator/prometheus` require an ADMIN session. Other Actuator endpoints (env, heapdump, shutdown, etc.) are not exposed. No paid monitoring provider is required. There is no external metrics collector or alerting service configured; these are instrumented endpoints ready for inspection/scraping.

`GET /api/live` checks Spring liveness without depending on PostgreSQL or Alpaca. `GET /api/ready` keeps the existing traffic-readiness and database writer ownership check. `/api/health` remains the legacy lightweight response. Render should continue using `/api/ready`.

## Local market SSE

`GET /api/events` is a public same-origin event stream. `connected` tells clients to refresh; `market` carries only `{ "symbol": "AAPL" }` invalidation hints, never private order/user data. Events are queued after transaction commit and successful engine publication; rollback emits no event. There is no durable event log or Last-Event-ID replay. On reconnect, the frontend reloads current state instead of assuming that it received every event. Polling stays active as a safety net, preserving existing values during transient errors. External Alpaca quotes still use their existing polling/cache path.

Heartbeat comments run every 15 seconds; streams expire after five minutes and EventSource reconnects. Hidden tabs close their stream and reconnect on visibility. Connections are bounded to 128 per process and 3 per client IP (NAT users share this); overflow returns 429. Each client coalesces up to 256 symbol hints; a bounded worker pool performs socket writes away from the trading lock. Slow connections can delay hints, so polling is still authoritative. This is local-instance best-effort delivery; deployment ownership handoff/reconnect and polling cover replacement, not cross-instance event broadcasting.

## Continuous integration

`.github/workflows/ci.yml` runs on pull requests and pushes to main with read-only repository permissions. It provisions disposable PostgreSQL 17, uses Java 17 and Node 24, checks types/lint/frontend tests/build, runs Maven verification and builds the Linux amd64 Docker image. Database credentials are CI-only fixtures, not hosted secrets. Failed test reports are uploaded; cloud credentials are not required, and no image or website is automatically published. The workflow is checked locally but its first GitHub-hosted run occurs after push.

Migration V11 adds request results and paper allocations. Applied migrations V1–V10 are unchanged. Startup runs V11 through Flyway; local and hosted data are not copied between environments. New tests run only against the dedicated local test database.
