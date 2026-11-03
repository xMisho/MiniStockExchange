# Deploy Verdex on Render Free + Neon Free

This is deployment preparation, not a published website. Use one Render Docker web service for the React UI and Spring Boot API, and a separate Neon PostgreSQL database. No purchased domain is needed: Render assigns an HTTPS `onrender.com` address.

## What is prepared

- `Dockerfile`: Node 24 builds/tests React, Maven/Java 17 packages it into Spring Boot, and a non-root Java 17 runtime runs the combined JAR.
- `.dockerignore`: only application build inputs enter the context. Private dotenv files, Git metadata, dependency folders and local build output are excluded.
- Maven `production` profile: copies `frontend/dist` and the chart-library notices into the JAR's static resources. Build the frontend first.
- Spring `production` profile: binds to Render's `PORT`, reads runtime database variables, honors trusted proxy HTTPS headers, enables Secure session cookies, and uses a small database pool.
- `render.yaml`: explicitly selects the Free plan, one instance, manual deploys, and `/api/ready` for startup readiness. It does not create a paid database or persistent disk.
- `/api/ready`: 200 only when Spring accepts traffic, after startup listeners including market restoration complete; 503 while starting/stopping. Readiness also checks current database writer ownership and fails when that check cannot be read. It does not check Alpaca availability or every database operation.

## Writer handoff and production review

**Do not publish trading before implementation review.** The existing matching service supports one active writer. Render can start a replacement before stopping the old process, including during platform maintenance. One configured instance and manual deploys alone do not rule out that temporary overlap.

The implemented protection is a production-only database ownership check, enabled by the `production` profile:

1. Add `exchange.market_writer` with one row containing an instance UUID and claim timestamp.
2. During startup, take the same advisory transaction lock used by trading, claim ownership, and reconstruct the engines in that transaction. Commit ownership only if reconstruction succeeds.
3. Before any coordinated trading transaction or cached engine read, verify the current instance still owns that row while holding the advisory lock.
4. Once a replacement commits its claim, the previous instance rejects local engine reads and trading with 503, rather than using stale memory. Historical database records remain durable.
5. If replacement startup fails before commit, its claim rolls back. If it fails after committing ownership, the previous instance remains blocked until a successful restart/redeployment claims ownership again. This intentionally prefers an interruption to inconsistent fills.

This is a single-writer handoff, not multi-instance trading support. Migration **V10** adds the ownership row. Local development/tests keep fencing disabled by default; do not run an unfenced development instance against the hosted database. No ownership check can protect against an older application version that does not participate in the protocol.

The handoff has been implemented for local verification, following the requested timing checks; production approval remains separate. Tests use independent service instances and the real test database to cover simultaneous claims, an old order already in flight, an order arriving during replacement restoration, restoration failure before ownership commits, and a simulated crash immediately after commit.

### Recovery procedure

- **Replacement fails before ownership commits:** the transaction rolls back. Keep the old healthy instance running, fix the failed startup, and retry a manual deployment.
- **Replacement fails after ownership commits:** the old instance returns 503 for local reads/trading and readiness. Do not clear the ownership row or disable fencing to force it to trade. Restart/redeploy a known-good version that includes fencing, using the same database; it claims ownership while rebuilding all committed state.
- Verify `/api/ready` returns 200 on the replacement, then verify its local snapshot and durable order history. The old instance must stay unready. Leave uncertain submissions unretried until their durable history has been checked.
- Never roll back to a pre-fencing build against an active fenced market. Shut down all writers first and use a reviewed recovery version. The ownership row is deliberately not a time-expiring lease: a slow startup must not revive a stale writer.

## 1. Create a Neon Free project

Use https://console.neon.tech/ and select the Free plan. Choose PostgreSQL 17 and a region near the Render region when available. Create a fresh database for Verdex; do not point the public app at your local development/test database.

In Neon's **Connect** dialog select **Java**, and use a direct, non-pooler hostname for this first deployment. Set these runtime values separately:

```text
DB_URL=jdbc:postgresql://YOUR_DIRECT_NEON_HOST/neondb?sslmode=require&channelBinding=require
DB_USERNAME=YOUR_NEON_ROLE
DB_PASSWORD=YOUR_NEON_PASSWORD
```

Keep credentials out of the JDBC URL, Git, screenshots, frontend environment variables and Docker build arguments. Do not paste passwords here. The URL starts with `jdbc:postgresql://`, not `postgresql://`; there are no shell quotes around values entered in Render's Environment tab.

Flyway creates the schema/tables on the first successful startup; Hibernate validates them. Your Mac's users, admin role, stocks, trades and watchlists are not automatically copied to Neon. This deployment starts with a fresh database.

### Linked Neon CLI setup

The root `neon.ts` declares Postgres only; existing Spring Security authentication and Flyway migrations remain authoritative. The Neon plugin includes agent skills and an OAuth MCP server; a second MCP installation for the same agent is unnecessary.

`neon deploy` reconciles the linked database services, not the Render website. It pulls `DATABASE_URL`, `DATABASE_URL_UNPOOLED`, and `NEON_BRANCH` into the ignored local dotenv file. Keep the existing local `DB_*` settings so ordinary development and tests continue using local PostgreSQL.

For Render, privately map the direct `DATABASE_URL_UNPOOLED` into the three `DB_*` values shown above, using the actual database name (for example `verdex`). Use the existing PostgreSQL JDBC driver and small Hikari pool; the direct connection also supports startup Flyway migrations. Never expose database URLs in React or `VITE_*` variables. A private ignored `.env.neon` can hold these deployment values separately; do not commit it or load it for integration tests.

From the repository root, `npm run build` builds React and packages the combined Spring Boot JAR. It skips database tests during packaging; run the normal test suite separately against the dedicated local test database.

## 2. Create Render Free hosting after code review and approval

Use https://dashboard.render.com/ and connect GitHub. Once the reviewed deployment branch is merged/pushed:

1. Create a **Blueprint** using this repository and `render.yaml`, or create a **Web Service** with Language **Docker**, repository root build context and `./Dockerfile`.
2. Confirm **Free**, one instance, branch `main`, manual deploys, and health check `/api/ready`. Do not enable previews or create another trading writer against the same database.
3. Enter the three Neon variables privately. Set `SPRING_PROFILES_ACTIVE=production` and `SESSION_COOKIE_SECURE=true` (already in the Blueprint).
4. Render supplies `PORT`; leave Docker's start command unchanged. Do not set `DB_TEST_URL` or use the `test` profile.
5. Complete the writer-handoff review above before enabling the first hosted deployment. Recheck current Free-plan terms in the dashboard; do not approve paid upgrades.

The default image uses `-XX:MaxRAMPercentage=55.0` and exits on JVM out-of-memory. This is a conservative starting heap allocation, not a guarantee that the Free instance can hold unlimited market history. Inspect memory after deployment and keep demo data small.

## 3. External market data is optional

The first website can run with **no Alpaca credentials**. Markets shows its connection-required state while the local exchange remains separate.

Before displaying provider data publicly, verify your Alpaca account's current display/redistribution permissions. A personal API key or free IEX access does not itself establish public redistribution permission. Only after that verification, add `ALPACA_API_KEY` and `ALPACA_API_SECRET` privately in Render's Environment tab and deploy/restart. Rotate any keys previously exposed in screenshots before uploading them. No provider keys are sent to the frontend or baked into the image.

## 4. First account and local stocks

Register a new user through the hosted UI. In Neon's SQL Editor, promote only your own normalized username:

```sql
UPDATE exchange.users SET role = 'ADMIN' WHERE username = 'your_username';
```

Sign out and sign back in to refresh the role. Register stocks through **Local Exchange → Admin · Register local stock**, choosing explicit local reference prices. Those are sandbox inputs, not imported external quotes. No default admin password or privileged registration endpoint is provided.

## 5. Verify the hosted app

- `/` loads Verdex and `/assets/...` serves the actual JS/CSS files.
- `/#/markets`, `/#/exchange`, `/#/orders` and `/#/portfolio` work on refresh. Hash routes stay in the browser; no broad API-to-index fallback is used.
- `/api/health` and `/api/ready` return 200 after startup; protected routes still return 401 without a session and writes still require CSRF.
- On HTTPS, session and remembered-login cookies are Secure, HttpOnly and SameSite=Lax.
- Register/login/logout, refresh/session restoration, two-user local trading and My Orders/portfolio work from the one hosted origin.
- Restart and verify persisted orders, fills and remembered login recover. A normal in-memory session can be lost on restart; a checked remembered login can restore it.
- Never run automated integration tests against the hosted database.

## Local combined-JAR verification

With the private local `.env` configured, build from the repository root:

```bash
npm --prefix frontend ci
npm --prefix frontend run build
./mvnw -Pproduction clean verify
```

Run locally over HTTP (explicitly disable Secure cookies only for this local check):

```bash
SESSION_COOKIE_SECURE=false java -jar target/mini-stock-exchange-1.0.0-SNAPSHOT-boot.jar --spring.profiles.active=production
```

Stop the existing development backend first. This uses the configured development database; never run a fenced and an unfenced writer concurrently against the same database. Open http://localhost:8080; Vite is not needed for the combined-JAR check. Keep the normal two-terminal Vite workflow for development.

If Docker is installed, validate the actual image as well:

```bash
docker build --platform linux/amd64 -t verdex:local .
docker run --rm --platform linux/amd64 -p 8080:8080 --env-file .env \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/mini_stock_exchange \
  -e SESSION_COOKIE_SECURE=false verdex:local
```

The example assumes PostgreSQL runs on your Mac on port 5432. Stop the development backend before running the container against that same database. `localhost` inside Docker refers to the container, so the local example uses `host.docker.internal`; do not change the hosted Neon URL to that hostname. Linux amd64 is selected explicitly to verify the intended Render architecture, including when building on Apple Silicon.

## Free-tier limits and updates

Render Free sleeps after 15 minutes without user traffic and can take about a minute to wake. Neon has storage/compute limits and also scales down when idle. This is a portfolio demo deployment, not an always-on trading platform. Do not create artificial keep-alive traffic to evade provider limits. Keep the frontend's existing refresh-failure states and do not retry order mutations automatically.

Persistent data belongs in Neon. Render's filesystem can disappear on restart/redeploy. Do not use Render's Free PostgreSQL as the long-term database; that offering expires after 30 days. Keep backups/exports privately, and review usage limits before adding more demo traffic.

Automatic deployment is disabled. Before each manual deployment, run frontend checks and PostgreSQL integration tests against the dedicated local test database. Docker builds run frontend checks but skip backend database tests because build containers have no test database or runtime secrets. Verify the writer handoff and startup readiness after any platform move; do not horizontally scale this design.

Official references (checked 2026-11-03): [Render Docker](https://render.com/docs/docker), [Render Free limits](https://render.com/docs/free), [Render deployment lifecycle](https://render.com/docs/deploys), [Render Blueprint specification](https://render.com/docs/blueprint-spec), [Neon Free plan](https://neon.com/pricing), [Neon Java connection guide](https://neon.com/docs/guides/java), [Alpaca redistribution FAQ](https://alpaca.markets/support/redistribute-alpaca-api).


## Verification completed locally

- Production-profile resource packaging and full PostgreSQL-backed suite: 225 passing backend tests, one optional benchmark skipped.
- Frontend: 159 tests, type checking, lint and build passed.
- Real Chrome workflow against the combined JAR (no Vite): navigation, exact book-price selection, market fill estimates, partial execution/remainder cancellation, lifecycle and four responsive widths.
- Two actual Java processes against the test database: replacement readiness, old-process rejection, no duplicate fill, fail-closed replacement shutdown, restart restoration and remembered-login restoration.
- Actual HTTP responses with simulated trusted proxy HTTPS headers: Secure/HttpOnly/SameSite session and remembered cookies plus HSTS.
- Real Docker build/run completed with Linux amd64 and Java 17. Frontend checks ran inside the build image; the runtime was verified under a 512 MB memory cap, as the non-root `verdex` user with a read-only root filesystem and temporary `/tmp` storage.
- Container browser/API workflow, two-container ownership handoff, replacement shutdown and restart recovery all passed against the dedicated local test database. Test containers and scoped fixture records were removed afterward.
- Docker Desktop is installed on the development Mac. Open Docker Desktop before running Docker commands; new terminals have its user CLI directory on PATH. No Docker Hub account, image push or hosted deployment was needed for this verification.

No cloud accounts or resources were created, no application was published, and no runtime credentials were changed by these checks.
