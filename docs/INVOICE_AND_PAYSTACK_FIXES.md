# Invoice recording and Paystack: investigation and fixes (23 Nov 2026)

## Limits of this verification (read first)
- The Java backend could **not** be compiled or run here: Maven Central is blocked for this session (HTTP 403 from the egress proxy), so no Spring/JUnit test ran. Changed Java files were only checked for syntax with `javac` (no syntax errors; symbol resolution unchecked).
- Paystack could not be reached and there are no test keys here. No payment was initialised, verified or webhooked. **Nothing about Paystack is verified as working.**
- What did run, for real: PostgreSQL 16, all 27 migrations (V1-V28) applied in order with no error; `scripts/db-checks/invoice_db_check.sql` (5/5); gap-free numbering under 3 concurrent writers (120 numbers, 0 duplicates); `InvoiceMath` scenarios via `javac` (15/15); frontend bundle (0 errors) and `scripts/reminders-verify.mjs` (7/7).

## 1. Invoice recording failure
**Root cause: not established.** I could not reproduce the error without a running backend, and I will not name a cause I did not observe. Reading the full path (form, DTO, service, entities, V17/V28, ledger, audit) and exercising the schema in real PostgreSQL found the database layer sound. Defects that do exist, and could produce a failed or confusing save:
1. **Validation errors were unreadable.** The API answers 400 "Please check the highlighted fields" with a `fieldErrors` map; the invoice form showed only that sentence and highlights nothing, so any rejected field looked like an unexplained failure. Now the form says which field and why (`describeError`).
2. **Double submit could still fail.** Two identical create requests at once both passed the "already created?" check; the second then hit the unique index and returned 409 "conflicts with existing data". Now a per-request advisory lock makes the second wait and return the first invoice.
3. Before the last release, `/api/invoices` was hidden by the V1 scope switch (404 "not available"). That was fixed in the previous delivery; if your error predates that zip, this was the cause.
4. If the V28 migration is not applied to your database, Hibernate (`ddl-auto: validate`) rejects the new `client_request_id` column. Check `select version from flyway_schema_history order by installed_rank` shows 28 with no clash.

**Files:** `invoice/InvoiceService.java`, `invoice/InvoiceRepositories.java`, `src/lib/invoiceShare.js`, `src/pages/invoices/InvoiceForm.jsx`.
**Please send me** the exact message on screen and the backend log lines around the failure: with those the real cause can be pinned down.

## 2. Paystack integration failure
**Root cause: not established** (no logs, no credentials, no network to Paystack). Findings:
- Code review: initialisation, verification, signature check (HMAC-SHA512, constant-time), duplicate-settlement guards and the AMSATI/merchant split are implemented. `scripts/db-checks/paystack_separation_check.sh` passes: the platform key is read only by subscription billing and the startup validator; storefront code uses each merchant's own decrypted key.
- **Diagnosability defect (fixed):** checkout hid every Paystack failure behind "We could not start the payment" and logged only a bare message. Now the log records the HTTP status and Paystack's message (never a key) and the user sees a truthful reason (key refused, Paystack unreachable). `GET /api/subscriptions/billing-status` now also reports whether the key looks valid, test/live mode, whether `APP_ENCRYPTION_KEY` is set, and whether the return URL is set.
- **Configuration that must be present, or checkout fails:** `PAYSTACK_SECRET_KEY` (starts `sk_test_`/`sk_live_`, the AMSATI account), `APP_ENCRYPTION_KEY` (32 bytes base64; checkout refuses without it), `FRONTEND_URL`, `PUBLIC_API_URL`. In production the app refuses to start without them. Your earlier `.env` had duplicate keys where the last block wins, so check what the running process actually received.
- **Webhook (set in the AMSATI Paystack dashboard):** `{PUBLIC_API_URL}/api/webhooks/paystack/subscriptions`. Merchants get their own URL in Store settings.
- A merchant "Connect Paystack" error means that merchant's key was rejected (HTTP 401 from Paystack) or `APP_ENCRYPTION_KEY` is missing.

**Files:** `payment/PaystackClient.java`, `subscription/BillingService.java`, `subscription/BillingController.java`, `scripts/db-checks/paystack_separation_check.sh`.

## 3. Migrations
None added. V27/V28 from the previous delivery are unchanged.

## 4. Tests: actual results
| Check | Result |
|---|---|
| All migrations V1-V28 on PostgreSQL 16 | applied, no error |
| `invoice_db_check.sql`: numbering, duplicate request id, impossible money states, duplicate payment id, tenant lookup | 5/5 pass |
| 3 concurrent writers on the invoice counter | 120 unique, gap-free |
| `InvoiceMath` (totals, tax, discounts, invalid input, statuses) | 15/15 pass |
| Frontend bundle / `reminders-verify` (incl. error wording) | 0 errors / 7/7 |
| Paystack separation (static) | pass |
| Spring, JUnit, Vitest, real invoice API round trip, Paystack init/verify/webhook/duplicate webhook, subscription activation | **not run** |

## 5. What I need from you
1. Backend log lines and screen text from the failing invoice save and the Paystack error.
2. Confirm the running process has the four variables above (print only whether each is set).
3. Run `mvn test`, then a Paystack **test-mode** checkout with a test card, then replay a webhook from the Paystack dashboard.

## 6. Resolved?
Neither issue can be called verified resolved. The diagnosability and duplicate-submit defects are fixed in code (uncompiled); the root causes still need your log output.

## Addendum: dashboard error (my regression, fixed)
In the invoicing release I changed the dashboard's "customers paying what they owed" line to `customerPayments.sumBetween(...).add(...)`. That query is a plain SQL `sum()`, which is **null** when there are no repayments in the period, so the call threw a NullPointerException and the dashboard returned an error for any business with no customer repayments in the selected period. Fixed in `DashboardService.java` (the sum is null-safe again). The new invoice queries were also rewritten as plain single-value aggregates with null handled in `InvoiceReporting`, and `InvoiceReportingTest` now covers the null case. Not run (no Maven here); syntax-checked with `javac` only.
