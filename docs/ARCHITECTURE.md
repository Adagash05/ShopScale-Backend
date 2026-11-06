# Architecture (milestone 1)

## Tenancy
* One shared app and database. Every tenant table has `business_id` (foreign key, indexed).
* The tenant comes **only** from the verified JWT (`security/Tenant`). No endpoint accepts a business id from the client.
  Every repository query that touches tenant data filters on that id, and lookups by id are `findByIdAndBusinessId`.
* A user signs up first (no business), completes onboarding (`POST /api/businesses`), and receives new tokens carrying
  `bid` (business) and `role`. Role changes or membership removal take effect when the 15-minute access token expires.
* Roles: OWNER, MANAGER, SALES_STAFF, KITCHEN_STAFF are modelled and enforced with `@PreAuthorize`. Staff invitation
  and management screens are not built yet, so every account is currently an OWNER.

## Authentication
* BCrypt (cost 12). Access JWT (15 min). Refresh tokens are random 256-bit values stored only as SHA-256 hashes.
* Refresh rotates the token. Re-using an already-rotated token revokes the whole token family (theft detection).
* Login, register and refresh are rate-limited per client address (in memory; use a shared store for several instances).
* Not built yet: email verification and password reset (they need an email provider).

## Subscriptions (foundation)
* `SubscriptionState.compute` is the single place that turns stored status and dates into what a business may do:
  writable or read-only, plan limits, features. `SubscriptionService` exposes `requireWritable`, `requireFeature`,
  `requireItemCapacity`. `WriteGuardInterceptor` blocks writes when a subscription has ended; reads always work, so
  data is never held hostage.
* New businesses get a 14-day trial with Business-plan entitlements and no card. A trial that ends is read-only
  immediately. A paid subscription gets a configurable grace period (3 days).
* Not built yet: Paystack payments, renewals, upgrades/downgrades, cancellation, promo codes, admin screens.
  Until then plans can only be changed directly in the database.

## Sales and stock integrity
`SaleService.create` runs in one transaction: it locks the business row (serialising sales and giving gap-free sale
numbers), row-locks the variants, validates stock, takes prices and costs from the database (never the client), writes
sale, items, payments, stock movements and the customer balance together, and is idempotent through `clientRequestId`.
`StockService` is the only code that changes a quantity. Refunds are whole-sale and return stock once (a refunded sale
cannot be refunded again).

## Data model notes
* Stock lives on `product_variants`. A simple product has one variant with no label.
* Archiving a product never deletes it; sale items keep a copy of the name, price and cost at the time of sale.
* Money is `numeric(14,2)`. Dates for reports are resolved in the business timezone (default Africa/Lagos).
* Flyway: `V1__core_schema.sql`, `V2__seed_plans.sql`. Never edit an applied migration; add `V3__...`.
