# ShopScale billing: repairs, automatic renewal, test report and deployment

Scope: AMSATI subscription billing (Paystack platform account). Merchant storefront payments (each merchant's own Paystack key) were inspected and **not changed**.
Prices and plan limits come from `V15__final_pricing_limits_features.sql` and were **not changed** (verified after applying V1-V19: FREE 0/0, ESSENTIAL 4,999/49,990, GROWTH 11,999/119,990, BUSINESS 24,999/249,990).

## A. Root-cause report (found by reading the code, not by guessing)
| # | File | Defect | Effect | Correction | How checked |
|---|---|---|---|---|---|
| 1 | `BillingService.settle` | Paystack status `abandoned` marked the payment FAILED permanently. `abandoned` only means the customer has not finished paying. | A customer who paid after returning, or whose webhook arrived later, was charged but never activated. | Only `failed`/`reversed` end a payment. Rows already marked FAILED by the old code (note "Paystack reported: abandoned") are re-verified by `verify`/webhook (`settleable`). | Code review; **no execution** (needs Spring + Paystack) |
| 2 | `settle` | Verified reference, amount and currency only. Customer, business, plan, billing cycle and purpose were not checked; the Paystack transaction id column `paystack_transaction` was never written. | A mismatched transaction could settle the wrong order; no audit trail of the transaction. | `mismatch()` checks reference, amount, currency, customer email, and metadata business/plan/cycle/kind; transaction id persisted and unique (`uq_sub_payments_paystack_tx`). | SQL uniqueness test run on PostgreSQL; Java logic not executed |
| 3 | `quote` | A CANCELLED plan still inside its paid period was treated as "no paid plan", so paying again started a new period from today. | The customer lost the days already paid for. | CANCELLED inside its period counts as paid, so a new payment stacks on the remaining time. | Code review |
| 4 | `BillingService` notifications | Link `/plan`, but the route is `/more/plan`. | Dead link from every billing notification. | Links changed to `/more/plan`. | Route checked in `App.jsx` |
| 5 | `settle` | `plusMonths` from the previous end drifts after month ends (31 Jan -> 28 Feb -> 28 Mar). | Subscriptions starting on the 29th-31st lost days every month. | `RenewalDates.periodEnd` keeps the anchor day (clamped to month length). | **Run**: 14 assertions in a JDK harness, all passed |
| 6 | `BillingController.webhook` | Signature computed over a decoded `String`. | A character-set change could break the HMAC. | Controller takes `byte[]`; `PaystackClient.validSignature(byte[])`. The `String` overload stays for the merchant webhook. | Code review; **not executed** |
| 7 | `checkout` | `authorization_url` was not validated; `FRONTEND_URL` silently defaulted to localhost. | A blank/non-https checkout address could be returned; production callbacks could point to localhost. | Non-https address -> payment row FAILED and a clean error; blank `FRONTEND_URL` is refused. | Code review |
| 8 | `application.yml` / startup | Nothing validated production configuration. | Missing Paystack/encryption key or localhost URLs showed up only as runtime failures. | `ProductionConfigValidator`: with `APP_ENV=production` (default) startup fails with a list of problems (values are never printed). | **Run**: 5 cases in the JDK harness passed |
| 9 | `Plan.jsx` | The `?reference=` was removed from the address bar before verification finished; a dropped connection lost it for good. | Paid customers saw nothing and had no retry. | Reference is stored in localStorage first, kept until the server returns SUCCESS/FAILED/MISMATCH, auto-rechecked, with "Check payment status" buttons. | Test written (`plan.test.jsx`), **not run** |
| 10 | `Plan.jsx` | Text said payments are one-time and cards are never charged automatically; no renewal status. | Wrong once renewal exists. | Replaced with disclosure + consent checkbox and a live renewal panel. | Not run |

Not defects (checked): the settle row lock (`lockById`) and amount-in-kobo comparison were sound and kept; purchases are reported to analytics only after the backend returns SUCCESS (kept); the merchant storefront webhook verifies with the merchant's own decrypted key and shares only the HTTP client.

## B. Change report
**Migration** `V19__recurring_billing.sql` (additive): `subscriptions.auto_renew, billing_anchor, cancelled_at, auto_renew_consent_at`; `subscription_payments.customer_email, auto_renew_consent, consent_at, source` + unique index on `paystack_transaction`; new tables `billing_authorizations` (encrypted authorization, one active per business) and `subscription_renewals` (attempts; `UNIQUE(subscription_id, due_at, attempt_no)`).
**Backend new**: `RenewalService`, `RenewalDates`, `BillingAuthorization(+Repository)`, `SubscriptionRenewal(+Repository)`, `common/ProductionConfigValidator`, tests `RenewalDatesTest`, `RenewalServiceTest`, `ProductionConfigValidatorTest`.
**Backend modified**: `BillingService`, `BillingController`, `Subscription`, `SubscriptionPayment`, `SubscriptionRepository`, `SubscriptionPaymentRepository`, `PaystackClient` (`chargeAuthorization`, `find`, byte[] signature), `application.yml`, `docker-compose.yml`, `.env.example`.
**Frontend**: `src/pages/Plan.jsx`, `src/test/plan.test.jsx`.
**API**: new `GET /api/subscriptions/billing`, `POST /api/subscriptions/auto-renew {enabled, consent}`; `POST /api/subscriptions/checkout` accepts `autoRenew`, `consent`; payment history rows gain `source`. The authorization code is never returned.
**Config**: `APP_ENV` (default `production`), `BILLING_AUTO_CHARGE_ENABLED`, `BILLING_RETRY_DELAYS_HOURS` (default `24,48`). `app.subscription.grace-days` (3) unchanged.

## How automatic renewal works
1. Checkout shows the disclosure and an **unticked** box. Ticked = `autoRenew` + `consent`; the backend rejects `autoRenew` without `consent` and when `APP_ENCRYPTION_KEY` is unset.
2. After Paystack verification, if consent was given and Paystack marks the authorization `reusable`, the code is encrypted (AES-GCM, `SecretBox`) and stored with brand/last4/expiry only; the customer's email is stored because only that email can charge it. Raw card data is never seen. If the card is not reusable the plan is still activated, auto-renew stays off, and the customer is told.
3. `RenewalService.scheduled` (every 15 min, persistent, no timers in memory) claims due subscriptions `FOR UPDATE SKIP LOCKED`, commits an attempt row + payment row with a deterministic reference **before** calling Paystack (`charge_authorization`), then verifies with Paystack and settles through the same `settle()` as checkout. The price is read from the `plans` table at charge time.
4. Failure: attempt recorded with Paystack's `gateway_response`, subscription -> PAST_DUE (effective GRACE_PERIOD, still writable, inside the existing 3-day grace), customer notified, retries after 24 h and 48 h (configurable, never beyond the grace window, max 3 attempts). After the last attempt automatic renewal is switched off. A successful manual payment or retry restores ACTIVE.
5. Crash/restart safety: an attempt left PENDING (process stopped, no answer from Paystack) is checked against Paystack by reference after 10 minutes; it is never re-charged. A charge waiting for bank authentication (`paused`) holds the attempt open for 24 h.
6. Cancel: sets CANCELLED, `auto_renew=false`; the scheduler never selects it; access continues until the period end; the saved card is kept for resume. Resume (`auto-renew` enable) needs consent, a saved reusable authorization and an unexpired period.
7. Dates: UTC, calendar months (see item 5 above); the frontend displays them in the browser's local time.

## C. Test report: what ran and what did not
**Ran and passed**
- Migrations V1-V19 applied to PostgreSQL 16 from scratch; V19 applied over a database holding an old subscription and payment: both rows preserved.
- Prices after migration match the table above.
- SQL constraints: duplicate attempt rejected; invalid status / attempt number rejected; duplicate Paystack transaction id rejected; duplicate reference rejected; invalid `source` rejected; second active authorization for one business rejected.
- Due-subscription query: returned exactly the 4 eligible rows of 6 (cancelled and non-auto excluded). Two concurrent sessions running the claim with `FOR UPDATE SKIP LOCKED` got 3 and 1 rows with no overlap.
- `RenewalDates` (month ends, leap years, annual, December rollover) and `ProductionConfigValidator`: 14 assertions in a JDK harness, 0 failed.
- Syntax-only check of the backend sources and tests, and of `Plan.jsx` and `plan.test.jsx`: no syntax errors.
- Previously passing Node suites (domains 9, theme 4, GA 12) were not affected by this change and were not re-run for it.

**Not run (no Maven, npm, browser or Paystack access in this environment)**
- `mvn test` / compilation against Spring and Hibernate (type errors are possible; the code was reviewed by hand).
- JUnit: `RenewalDatesTest`, `RenewalServiceTest`, `ProductionConfigValidatorTest` (their logic was run in the harness above, not through JUnit).
- Any integration test of `settle`, `checkout`, `verify`, the webhook, `RenewalService`, Hibernate mapping of the new entities (`ddl-auto: validate` will check at startup), or concurrent verify + webhook against a real database.
- `vitest` (`plan.test.jsx`), `vite build`.
- **Paystack sandbox**: no initialize, charge, webhook or verify call was made. No test credentials were available.
- Merchant storefront regression: code paths inspected and untouched except the shared `PaystackClient`, which only gained methods; **not executed**.
These must be run before release; see "First actions" below.

## D. Deployment
Environment (EC2). Never commit values; keep the encryption key identical across restarts and releases or saved authorizations become unreadable (merchants' stored Paystack keys too).
```
APP_ENV=production
DB_URL=jdbc:postgresql://<supabase-host>:5432/postgres   DB_USERNAME=...   DB_PASSWORD=...
JWT_SECRET=<32+ chars>
PAYSTACK_SECRET_KEY=sk_live_...          # AMSATI platform account
APP_ENCRYPTION_KEY=<openssl rand -base64 32>
FRONTEND_URL=https://app.shopscale.com.ng
PUBLIC_API_URL=https://api.shopscale.com.ng
CORS_ALLOWED_ORIGINS=https://app.shopscale.com.ng,https://shopscale.com.ng,https://www.shopscale.com.ng
COOKIE_SECURE=true
# optional: BILLING_RETRY_DELAYS_HOURS=24,48   BILLING_AUTO_CHARGE_ENABLED=true
```
Paystack dashboard (AMSATI account): Settings > API Keys & Webhooks > set the **webhook URL** to `https://api.shopscale.com.ng/api/webhooks/paystack/subscriptions` (one URL for the platform account; the merchant URLs `/api/webhooks/paystack/store/{slug}` stay on merchants' own accounts). Use test keys and the test webhook URL first.
External dependencies that remain and cannot be done from code: Paystack's own enablement of recurring charges on the AMSATI account (confirm with Paystack), live-mode approval, DNS/TLS for the API host, and whether the customers' banks require 2FA on recurring charges (handled as "pay manually" notice, not as a bank redirect).
Not implemented: a standalone card-verification charge (Paystack suggests a small first charge, refunded or credited); "Update payment method" is done by paying with the box ticked. Customer notices are in-app notifications only (no email/SMS sender exists in the billing path).
**First actions on a networked machine**: `mvn test`; start with `APP_ENV=development` against test keys; checkout monthly and annual with the box ticked and unticked; force a renewal by setting `current_period_end` to the past on a test subscription; simulate failure with a Paystack test card that declines; send a signed webhook twice and concurrently with the return page; run the merchant storefront checkout.
