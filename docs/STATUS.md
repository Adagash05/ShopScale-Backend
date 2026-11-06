# Status: milestone 1

## Built
Registration, login, logout, refresh rotation · business onboarding with store slug check · 14-day trial and plan
enforcement · categories, products with variants, archive/restore · stock adjustments, movements, low stock ·
customers, credit and repayments · POS sales (atomic, idempotent), receipts data, refunds · expenses ·
data-backed dashboard with all required date ranges · audit log writes · OpenAPI.

## Not built yet (nothing in the UI pretends otherwise)
Food businesses (menu, modifiers, kitchen, tables, small-chops scheduling, deposits) · online storefront and orders ·
Paystack (merchant and platform) · subscription payments, renewals, upgrades, downgrades, cancellation, promo codes ·
staff management · super admin · custom domains · password reset and email verification · supplier balances, cash and
bank records · CSV import/export · image upload · notifications · PWA/offline.

## Test results
* Backend: **not run**. Maven Central was unreachable in the authoring environment. Unit tests exist for date ranges,
  slug rules, subscription state and JWT handling (`src/test`). Run `mvn -B verify`.
* No integration tests exist yet (they need PostgreSQL). Tenant isolation, atomic sales and webhook idempotency are
  designed in but **not yet proven by automated tests**.
* Frontend: 22 tests pass, production build passes (see the frontend README).

## Known limitations
* Plans can only be changed in the database until billing is built.
* Role/membership changes apply when the 15-minute access token expires.
* The login rate limiter is per instance.
* `Sale` numbers come from a per-business counter; the business row lock serialises sales per business.
* Whole-sale refunds only; no partial returns.
