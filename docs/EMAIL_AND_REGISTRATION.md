# Registration, email delivery (Resend) and verification

## What was wrong
1. `AccountService.sendVerification` / `issue` sent the email over SMTP **inside the database transaction**. While the provider answered
   (no mail timeouts were configured, so potentially minutes), the transaction kept its row locks on `auth_tokens`.
   The next request touching the same user (resend, a second click) ran `update auth_tokens set used_at=? where user_id=? and kind=? and used_at is null`,
   waited on those locks, and hit the PostgreSQL statement timeout (SQLSTATE 57014). The caller saw a 504. This was reproduced on PostgreSQL 16 (see Tests).
2. No index served that update, so it scanned `auth_tokens` while holding locks (fixed by V22).
3. Verification links could be consumed twice by simultaneous requests (read-then-write). Now an atomic conditional update.
4. A simultaneous double sign-up with the same email could surface as a 500 instead of "already exists".
5. `resend-verification` had no rate limit and shared one bucket with sign-in.
6. Light mode was not the default: with nothing stored the theme was "system", so dark-OS devices got dark mode.

## What it does now
* Register: validate, create user, create hashed token, **commit**, then the email is queued. The response does not wait for Resend.
* `EmailService.sendAfterCommit` runs after commit only (a rolled-back sign-up sends nothing), on a 2-thread pool, 3 retries (5 s, 30 s, 120 s).
  Final failure is logged at ERROR with the provider's reason (never the token, key or full address) and the user can press "Send the email again".
  Limitation: a retry pending at the moment the server stops is lost; the resend button is the recovery. (A persistent outbox would remove this; it would have to store the raw link, which is why it was not added.)
* Resend: locks the user row (waits at most 3 s), retires old tokens, issues a new one. A second request within 60 s is a quiet no-op (an email is already on its way).
* Verification and reset links: SHA-256 hashed in the database, single use (atomic), expire (3 days / 1 hour).
* Rate limits: register, resend-verification and forgot-password: 10 per minute per address each (own buckets); everything else under `/api/auth`: 30.
* SMTP timeouts: connect 5 s, read/write 10 s. STARTTLS and auth default to on.
* Startup check (`MailConfigDiagnostics`): nothing set = warning only; partly set or malformed `MAIL_FROM` = refuses to start in production.

## Environment variables
| Variable | Value |
|---|---|
| `MAIL_FROM` | `ShopScale <no-reply@shopscale.com.ng>` |
| `SPRING_MAIL_HOST` | `smtp.resend.com` |
| `SPRING_MAIL_PORT` | `587` |
| `SPRING_MAIL_USERNAME` | `resend` |
| `SPRING_MAIL_PASSWORD` | your Resend API key |
| `FRONTEND_URL` | `https://app.shopscale.com.ng` (used in every email link) |
`SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` / `..._STARTTLS_ENABLE` are optional now (default true). No new variables were added.

## Deploy
1. In Resend: add the domain `shopscale.com.ng`, add the DNS records Resend shows (SPD/DKIM, plus the return-path record), wait until it shows Verified. Create an API key with "Sending access".
2. Set the variables above on the backend host. Rotate any key that was ever pasted into a file or chat.
3. Deploy the backend: Flyway applies `V22__auth_tokens_open_index.sql` on start (small, non-destructive; `CREATE INDEX IF NOT EXISTS`).
4. Deploy the frontend with `VITE_API_BASE_URL=https://api.shopscale.com.ng`.
5. Look for `Email configured: host=smtp.resend.com` in the startup log, register a test account, and expect `Email '...' delivered to the mail provider` in the log within seconds.
6. If the reverse proxy times out at 30–60 s, that is no longer hit by sign-up; keep it at 60 s or more for other endpoints.
