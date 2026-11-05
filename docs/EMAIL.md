# Email delivery and account recovery

Email is optional and **disabled by default**. Verdex uses the official Resend Java SDK (pinned to 4.30.0) over HTTPS, so it does not depend on SMTP ports blocked by Render Free. No personal inbox password is needed. The app keeps a sending-only key; domain and hosted-template management require separate setup permissions. Existing local configuration and accounts are preserved.

## Provider setup

1. Create an account at https://resend.com/signup and complete its verification yourself.
2. For a private first test, use Resend's `onboarding@resend.dev` sender and **only your own Resend account email** as the recipient. This is a restricted development sender, not a way to email arbitrary users.
3. Create a sending API key in Resend and save it privately as `RESEND_API_KEY`. Never paste the key into source, chat, screenshots, or Git.
4. To send to other users, add a domain you control in Resend and verify its DNS records. Then use an address on that verified domain, for example `Verdex <notifications@your-domain.com>`. A Render subdomain is not an email domain you control. No purchase or provider account is created by the code.

Set these in the private root `.env` locally, or Render's Environment settings when hosted:

```dotenv
EMAIL_ENABLED=true
RESEND_API_KEY=your-private-sending-key
EMAIL_FROM=Verdex <onboarding@resend.dev>
APP_PUBLIC_URL=http://localhost:5173
EMAIL_ENCRYPTION_KEY=your-private-base64-key
EMAIL_ALLOWED_RECIPIENT=your-own-resend-account-email
```

Generate the encryption key once using `openssl rand -base64 32`. Keep the same key across restarts and deployments so pending messages can be decrypted. Do not put any of these real values in `.env.example`. For production, set `APP_PUBLIC_URL` to the exact HTTPS frontend URL; reset URLs never come from an untrusted Host header. Change `EMAIL_FROM` to your verified-domain sender and remove the recipient restriction only when ready to email other users. No external sends are performed by the ordinary test suite.

Restart Spring after changing configuration. Startup fails if email is enabled without required sender/key/encryption/origin settings. With email off, in-app alerts work and the recovery form honestly reports that delivery is unavailable.

## Review the flows

- **Recovery:** Sign in → Forgot password? → enter the registered email. Existing and unknown addresses receive the same `202` message. Open the email link and explicitly submit a new password twice. Links are single-use and expire in 30 minutes. The link is in the URL fragment (not an HTTP query), is scrubbed from browser history on opening, and is not put in local/session storage. Passwords retain the existing 15–128-character rule. Reset does not log the user in.
- **Session revocation:** A reset increments the user's credential version and deletes persistent remember-me tokens atomically. Existing authenticated sessions are rejected on their next request. Requests already past the check may finish. Remember-me restoration and password reset take the same user lock; a stale token cannot restore a new session across revocation.
- **Email alerts:** Markets → Price alerts → Email notifications → Send verification link. Confirm ownership using the single-use verification link (24-hour expiry), then explicitly enable the email checkbox. Existing accounts are unverified and opted out. Verification alone does not enable alert emails.
- **Crossings:** The existing fresh/newer IEX observed-crossing rules are preserved. Recording a trigger and queuing its email happen in the same SQL transaction. A unique alert deduplication key prevents repeated polling from enqueueing duplicates. Email preferences apply to future crossings; old triggers are not backfilled. Opting out cancels pending alert messages, but an already-sending message may complete.

A bounded server worker checks up to 20 opted-in users per minute in round-robin order; with more users, the interval increases. Cache/provider limits remain active. **Render Free can sleep**: checks do not run while the process is asleep/offline, missed intermediate crossings are not reconstructed, and notifications are not guaranteed real-time. Opening Markets still performs its existing checks. No fabricated crossings are inferred.

## Delivery guarantees and operations

The transactional `exchange.email_outbox` stores AES-256-GCM encrypted message payloads. Recovery/verification token hashes are stored separately in `exchange.email_tokens`. Passwords are never emailed. Successful, cancelled, expired, and exhausted jobs clear their payloads; terminal metadata remains up to 30 days, and expired tokens up to one extra day. Cleanup resumes when email workers are enabled.

Delivery runs after commit, with row locking/skip-locked claiming, 60-second leases, a 10-second HTTP timeout, and at most eight attempts with exponential backoff (30 seconds up to 15 minutes), respecting a longer provider `Retry-After`. Non-retryable authentication/validation/provider permission errors fail immediately. A crashed lease can be reclaimed. The same UUID-based Resend idempotency key and exact encrypted-then-decrypted payload are reused across retries. Jobs expire within at most 24 hours (reset 30 minutes, alert 6 hours), inside the provider's idempotency retention window. No email body, token, recipient, or API secret is intentionally logged by the worker. Keep request-body/SQL parameter TRACE logging disabled in deployed environments.

`SENT` means **accepted by the provider**, not confirmed inbox delivery. Check Resend's dashboard for delivery/bounce events. Webhook-driven delivery/bounce tracking, automatic suppression, and email-based MFA are not implemented. A provider rejection is retried within the bounds and eventually fails; no claim is made that an email reached the inbox. The application never automatically retries an order as part of email delivery.

An authorized operator can inspect metadata without exposing payloads:

```sql
SELECT id, kind, status, attempts, created_at, expires_at, provider_id, failure_code
FROM exchange.email_outbox
ORDER BY created_at DESC LIMIT 50;
```

Do not manually replay old jobs under new idempotency keys or change encryption keys with pending jobs. Correct provider configuration and request a new recovery/verification link if a job has failed. An encryption-key rotation needs a deliberate queue migration/drain. The current transport uses one provider; no silent provider fallback is implemented.

Recovery POSTs share a process-local per-IP budget of 5/hour (configurable as `verdex.rate-limit.recovery`). Issuance additionally uses a database-backed per-account cooldown of one minute and maximum five links/hour per purpose. CSRF remains required, including anonymous recovery/confirmation. There is no account-existence field in public responses; request latency is not a cryptographic constant-time guarantee.

## API

| Method | Path | Access / result |
| --- | --- | --- |
| POST | `/api/auth/forgot-password` | Public + CSRF; `{email}` → generic 202 |
| POST | `/api/auth/reset-password` | Public + CSRF; `{token,password}` → 204 |
| POST | `/api/auth/verify-email` | Public + CSRF; `{token}` → 204 |
| POST | `/api/auth/email-verification` | Signed in + CSRF; queues link to own email → 202 |
| GET | `/api/auth/email-preferences` | Signed in; own configured/verified/alertEmails flags |
| POST | `/api/auth/email-preferences` | Signed in + CSRF; `{alertEmails}` → 204 |

Invalid/expired/consumed links return 400, throttled requests 429, and missing delivery setup 503. Ordinary account registration does not send unsolicited emails.

## Current setup verification

The sandbox sender was configured privately and two messages were sent through the real application flows to the approved Resend account inbox. The repeated verification send used the same idempotency key and returned the original provider email ID. Verification, reset, session revocation, and changed-password login completed using a temporary test account, which was removed afterward. Existing accounts were untouched. Provider acceptance and idempotency were checked. The recipient confirmed receipt of both messages: verification reached spam, and password reset reached the main inbox. Sandbox sender delivery does not guarantee future inbox placement.

The official Resend MCP server was added to the local Codex configuration and authenticated. The `resend`, `resend-cli`, and `email-best-practices` skills were installed globally, preserving other skills and MCP entries. The newly registered MCP tools are not exposed in this already-running agent session. The runtime key is sending-only, so it cannot manage domains or hosted templates. There is no custom domain to configure yet. Current messages use plain text; no hosted template was created or published. A future template setup should use the management connection, read the exact template variables, and keep the app key sending-only.

## Validation

```bash
./mvnw -Dtest=RecoveryApiTest,ResendTransportTest,AuthenticationTest,RateLimitFilterTest test
npm --prefix frontend test
npm --prefix frontend run build
```

Tests use the dedicated local PostgreSQL test database and fake provider transport/loopback HTTP server. They prove application behavior without sending real email or validating DNS/inbox deliverability. Live delivery must be tested after configuring your provider: request a link for your own inbox, confirm it, reset the password, and verify old credentials fail. Do not enable public recovery until that check passes.
