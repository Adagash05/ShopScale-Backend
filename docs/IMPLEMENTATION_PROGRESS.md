# ShopScale V1 - implementation progress

Last updated: 2026-11-10. Source of truth for what is built, verified and deferred. Backend: Spring Boot 3 / Java 21 / PostgreSQL / Flyway (V1-V15).

## Verification status (honest)
| Check | Status |
|---|---|
| Java syntax of every source file (javac parse) | Done, no errors |
| Maven compile, unit tests (`mvn test`) | **NOT RUN** - the build sandbox had no network, so Maven could not download dependencies. Run `mvn -B verify` first. |
| Migrations V1-V15 on PostgreSQL 16 | **Applied successfully, in order, to an empty database** with psql (not through Flyway itself). Plan rows and feature lists were queried afterwards and match the approved configuration. Entity fields were cross-checked against the resulting columns (heuristic script, no mismatches). |
| Real Paystack calls (merchant and AMSATI) | **NOT TESTED** - needs real keys. Signature, amount and currency checks are implemented and unit-structured, never exercised against Paystack. |
| Frontend build / tests | NOT RUN (see frontend progress file). |

## Completed (code written)
- Core POS, inventory (branch-aware, single stock writer), sales with refunds/returns, quotes and sales orders, receipts.
- Wholesale: price tiers, bulk price breaks, agreed customer prices. Prices always computed on the server.
- Purchasing, purchase orders, suppliers and balances, customer balances and part payments.
- Branches and stock transfers; employees, roles and permissions; invitations.
- Finance: expenses with approval limit, cash/bank accounts, shifts, ledger.
- Accounts: password reset, email verification (email sending optional), in-app notifications, activity history.
- **Plans (final approved configuration, V15)**: FREE ₦0; ESSENTIAL ₦4,999 / ₦49,990; GROWTH ₦11,999 / ₦119,990 (flagged `recommended`); BUSINESS ₦24,999 / ₦249,990. Limits (users / locations / products / customers / expenses / suppliers): Free 1/1/15/15/15/5; Essential 2/1/100/100/100/20; Growth 5/2/500/unlimited/unlimited/50; Business 10/5/unlimited x4. Unlimited = NULL. Enforced in `SubscriptionService` (users, locations, active products, customers, expenses, suppliers); archived products do not count. A lapsed paid plan or trial falls back to Free (writable, no deletion). Switching to a plan is refused while users/locations exceed its limits. STARTER is hidden, not deleted. Online store and online orders start at Growth, custom domain at Business, CSV export at Essential.
- **Online storefront** (`store/`): settings, public catalogue (server pricing, stock check), orders, product images (DB, magic-byte validated, 600 KB), custom domain with DNS TXT verification (Business plan).
- **Merchant Paystack**: each merchant's own keys, validated against Paystack, stored AES-GCM encrypted (`APP_ENCRYPTION_KEY`), never returned. Initialize -> customer return `verify` and signed webhook `/api/webhooks/paystack/store/{slug}` (HMAC-SHA512 with that merchant's secret, then re-verified by API). Amount (kobo) and currency must match, idempotent, a sale is created in its own transaction; failures are flagged on the order ("needs attention"), never lost.
- Merchant order management: NEW -> CONFIRMED -> READY -> COMPLETED, cancel (blocked once a sale exists), pay-later completion with payment method, notification on new order.
- **ShopScale billing** (`subscription/BillingService`): AMSATI Paystack account (`PAYSTACK_SECRET_KEY`). Checkout, server-side verify, signed webhook `/api/webhooks/paystack/subscriptions`, one-time activation, payment history, failed/mismatch handling, cancel (access until period end), upgrade/downgrade with prorated credit and seat/location fit checks, daily expiry reminders. A subscription is **never** activated from a redirect.
- CSV export (products, customers, sales, expenses) on all plans.
- Offline POS support is server-side idempotency by `clientRequestId`.

## Not built / limits
- **No automatic card charging.** Plans are prepaid for one month/year; renewal is a new checkout (reminders are sent).
- Seat enforcement blocks adding users beyond the plan; it does not lock out existing users after a downgrade by lapse.
- CSV **import** is not built.
- Email delivery needs SMTP settings; without them links are shown to the user.
- Merchant payments are not posted to the cash ledger (money settles to the merchant's Paystack account).
- Custom domain needs DNS (CNAME + TXT) and TLS at your host; serving the SPA on that host is a deployment task.

## DEFERRED (post-V1, per instruction 2026-11-10)
Restaurant (menus, modifiers, tables, kitchen), recipes/production, wastage, catering. Backend code and migration V12 are kept as-is (`recipe/`, `restaurant/`) but have **no UI**, are untested and not advertised. Livestock: choice is stored; no livestock features exist.

## Notes on plan features
Several feature flags (STAFF_PERMISSIONS, ADVANCED_REPORTS, ENHANCED_DASHBOARD, ADVANCED_FINANCE, LOCATION_REPORTING, ADVANCED_STAFF_PERMISSIONS, PRIORITY_SUPPORT, CONSOLIDATED_REPORTING, ADVANCED_INVENTORY, ACTIVITY_HISTORY) are assigned per the approved plan table but are not all enforced by a dedicated screen or check yet; the enforced flags are POS-adjacent basics, PURCHASING (purchase orders), SUPPLIER_BALANCES, DATA_EXPORT, CASH_ACCOUNTS/RECONCILIATION, BRANCH_TRANSFERS, STOREFRONT, CUSTOM_DOMAINS, WHOLESALE_PRICING. Restaurant/catering flags (V12) remain granted to Essential-Business but those modules are deferred and not advertised. "Basic purchasing" (Essential) and "advanced purchasing" (Growth) are not separated in code: both use PURCHASING.
