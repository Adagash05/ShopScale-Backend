# ShopScale Partners (affiliate / influencer programme)

A partner recommends ShopScale and earns commission on the subscription payments ShopScale **actually receives** from the businesses they referred. Everything here runs on the existing app: same login, same Paystack webhook, same email service. There is no separate payment system.

## Fixed commercial terms (defaults, admin-editable)

| Setting | Default | Notes |
|---|---|---|
| Commission rate | 30% | of the amount actually collected (after discounts, never on refunded money) |
| Commission period | 12 months | per referred customer, counted from their first successful payment |
| Attribution window | 30 days | click to registration; the most recent partner link wins |
| Holding period | 14 days | pending, then available |
| Minimum withdrawal | ₦10,000 | |
| Currency / payout | NGN / manual bank transfer | recorded by an admin with a transfer reference |

Changing settings (Admin > Settings) is audited and only affects people who **register afterwards**. Each referral stores the rate, months and holding days that applied at registration, and every commission stores its own rate, so earned commission never changes.

Not built (excluded by the brief): performance bonuses, automated Paystack transfers, multi-level commission.

## How it works

1. **Apply.** `/partners/apply` creates a normal ShopScale account if needed, then the application (`PENDING`). A partner needs no business.
2. **Approve.** An administrator approves (`APPROVED`), which issues an 8-character code and referral URL `…/register?ref=CODE`. Statuses: PENDING, APPROVED, REJECTED, SUSPENDED. Suspended partners earn nothing new and their links stop attributing; earned balances are kept.
3. **Click.** The app host stores `?ref=` (last wins, 30 days) and reports one click per partner/visitor/day (`POST /api/public/partners/click`).
4. **Register.** The register request carries `referralCode` and an anonymous `visitorId`. After the registration commits, the server decides attribution (never blocking sign-up): the latest click by that visitor inside the window wins; a code-only fallback is stored as source `CODE`. Self-referral (same account, same normalised email, same last 7 phone digits) is refused. A customer is attributed once and can never be re-attributed (unique index).
5. **Pay.** `BillingService.settle` marks a subscription payment `SUCCESS`; after commit, `CommissionService.afterPaymentSettled` records **one** commission (unique `payment_id`, `ON CONFLICT DO NOTHING`), so webhook retries and the verify endpoint cannot double-count. A failure is logged and retried by a 15-minute reconcile job (90-day sweep). It never breaks billing.
6. **Window maths.** `commission = amount × rate × eligible fraction`, HALF_UP to kobo. A payment collected at or after `firstPaidAt + months` earns nothing; an annual payment is prorated to the part of the service period inside the window; early renewals do not double count.
7. **Release.** After the holding period `PENDING` becomes `AVAILABLE` (scheduled every 15 minutes and when the partner opens the dashboard).
8. **Withdraw.** A partner needs bank details, no other open withdrawal, and at least the minimum available. The whole available balance is reserved (`RESERVED`). Admin flow: `REQUESTED → APPROVED → PROCESSING → PAID`, or `REJECTED` / `FAILED` / `CANCELLED`, which release the reservation. `PAID` requires a unique transfer reference, the exact amount, and records administrator and time. Administrators cannot process their own withdrawal.
9. **Refunds / chargebacks.** Webhook `refund.processed` and `charge.dispute.resolve` (merchant-accepted) reverse the matching commission proportionally to cumulative refunds (idempotent by refund reference). If the commission was already reserved in a processing/paid withdrawal, or paid, a negative `CLAWBACK` row is created and netted from the next withdrawal; balances never go negative for the partner. Admins can also record a refund by hand (Ledger > Record refund) and reverse a single commission with a reason.

## Ledger guarantees

* Balances are never stored: they are sums of `amount − reversed_amount` over ledger rows.
* Kinds `EARNED`, `ADJUSTMENT`, `CLAWBACK`; statuses PENDING, AVAILABLE, RESERVED, PAID, REVERSED, CANCELLED. A `CANCELLED` zero row marks "paid, but outside the window / no commission" so the payment is not evaluated again.
* Commission rows cannot be deleted; event, refund-event and audit tables are append-only (database triggers). Check constraints stop reversals above the commission and `PAID` without reference, time and administrator.
* One open withdrawal per partner (partial unique index); a transfer reference can be used once (case-insensitive).
* Manual adjustments need a reason and cannot push a balance below zero.

## Security

* Partner endpoints work only on the caller's own partner record; no partner id is accepted from the client.
* **Platform administrators** are accounts whose **verified, active** email is in `PLATFORM_ADMIN_EMAILS` (comma-separated). There was no platform-admin role before, and business roles (Owner etc.) deliberately grant nothing here. Checked by the `@partners` bean on every admin endpoint; `GET /api/admin/partners/access` tells the UI.
* Bank account numbers are AES-GCM encrypted with the existing `SecretBox` (`APP_ENCRYPTION_KEY` required; saving is refused without it, never stored in plain text). Lists, e-mails, CSV exports and logs show only the last 4 digits. An administrator sees the full number only through an audited endpoint, only while a transfer is due (`APPROVED`/`PROCESSING`).
* Customers are shown to partners as "Ada O."; admins see full names and emails.
* Rate limits: click endpoint and partner endpoints have their own buckets in `RateLimitFilter`.
* All money is computed server-side from `subscription_payments`; the client never sends an amount that is trusted (the amount an administrator enters when marking a withdrawal paid must equal the stored withdrawal amount).
* CSV export neutralises spreadsheet formulas.

## Emails

Through the existing `EmailService.sendAfterCommit` (after the database commit, with its retries), so an email problem can never roll back or corrupt money: application received (partner and administrators), approved, rejected, suspended, reactivated, commission earned, commission available, withdrawal requested (partner and administrators), approved, processing, paid, rejected, failed. Reversals are visible in the ledger but do not send an email.

## API

Public: `GET /api/public/partners/programme`, `POST /api/public/partners/click`.
Partner (`/api/partners`): `GET me`, `POST apply`, `PUT profile`, `GET dashboard`, `GET referrals`, `GET commissions`, `GET|POST withdrawals`, `POST withdrawals/{id}/cancel`, `GET|PUT bank-account`, `GET resources`.
Admin (`/api/admin/partners`): `GET access|overview|performance|audit`, `GET affiliates[?status&q]`, `GET affiliates/{id}`, `POST affiliates/{id}/approve|reject|suspend|reactivate`, `GET referrals`, `GET commissions` (filters: affiliateId, status, customer, payment, from, to), `POST commissions/adjust`, `POST commissions/{id}/reverse`, `POST refunds`, `GET withdrawals`, `POST withdrawals/{id}/approve|reject|processing|paid|failed`, `GET withdrawals/{id}/bank-details`, `GET|PUT settings`, `GET|POST|PUT|DELETE resources`, `GET export/{commissions|withdrawals|affiliates|referrals}`.

Register request additions: `referralCode` (≤24), `visitorId` (≤64), both optional.

## Screens

Public: `/partners` (landing, live programme terms, real plan prices, calculator, FAQ; no testimonials or income claims). Partner app: `/partners/apply`, `/partners/login` (sends to `/login?next=/partners/dashboard`), `/partners/dashboard|referrals|earnings|withdrawals|payment-details|resources|profile`. Admin: `/admin/partners` (+ `applications`, `partners`, `partners/:id`, `referrals`, `ledger`, `withdrawals`, `settings`, `resources`, `audit`). On the two-domain deployment `/partners` is served by the public site and everything under it by the app host; `/register?ref=` on the public host is forwarded to the app host with the query kept.

## Setup checklist

1. Run the new Flyway migration **V23__partners.sql** (automatic on start).
2. Set `PLATFORM_ADMIN_EMAILS` to the administrators' emails; each must register and verify their email.
3. Set `APP_ENCRYPTION_KEY` (already required for merchant Paystack keys).
4. Optional `PARTNERS_REFERRAL_BASE_URL` (default `FRONTEND_URL`) for the host in referral links; it must be the host that serves `/register`.
5. In the **AMSATI** Paystack dashboard, enable webhook events `refund.processed` and `charge.dispute.resolve` on the existing webhook URL.
6. Make sure email sending is configured (Resend / SMTP) so partners get notifications.
7. Review the legal pages (terms) so they mention the programme before launch.

## Known limits (deliberate)

* Withdrawals take the **whole** available balance, not a partial amount.
* Attribution is decided at **registration** only: a later click does not move an existing customer.
* The refund/dispute webhook payload fields were written from Paystack's documentation and **not checked against a live event**; until you have seen a real one, use Ledger > Record refund as the fallback, and watch the first refund.
* The platform-admin allow-list is an environment setting, not a database role.
* The design leaves room for an automated payout provider (`payout_method` column, status machine), but none is implemented.

## Verification status

Run here: the migration on PostgreSQL 16; a ledger scenario against that database (idempotent earning, hold release, reservation, reject/release, paid with reference, transfer-reference uniqueness, clawback, immutability triggers, duplicate referral and click constraints); the commission-math harness (examples above, 12-month window, annual proration, refunds, hold); Node tests for the frontend routing, referral capture and calculator; a full frontend bundle; a server-render smoke test of every new screen.
**Not run here** (no Maven, no vitest, no network): the Java services against Spring and JDBC, the JUnit tests in `src/test/java/.../partners`, a real Paystack refund event, real email delivery, `vite build`. Run `mvn test` and `npm test`/`npm run build` before launch.
