# Production fixes: checkout, stock notifications, merchant Paystack, platform admin, media (2026-11-14)

Honesty note: this environment has no Maven, no npm registry, no Paystack and no browser run against the real stack. Everything below says exactly what was run and what was not.

## A. Bugs

### 1. Stock notification opens a 404
* **Root cause:** `StockService` published the low-stock notification with link `/stock`. The page is registered at `/more/stock` (`App.jsx`). Notifications already saved keep the old link.
* **Also found:** the kitchen "order ready" notification linked to `/more/restaurant`, which has no page in the app.
* **Fix:** `StockService` -> `/more/stock`; `KitchenOrderService` -> `/sell`; `src/lib/notificationLink.js` remaps the two old saved links when a notification is opened (`Notifications.jsx`), so notifications already in the database also work. The Stock page has no per-product deep link, so no product context is passed.
* **Tested:** new `scripts/notification-links-verify.mjs` reads every link the backend publishes and checks it against the real route table (App.jsx plus module registries). **It fails on the original code** (`/stock, /more/restaurant`) **and passes now**. Same links work on desktop and mobile because it is one router.
* **Result:** fixed and verified at route level. Not clicked in a browser.

### 2. Storefront checkout: "Something went wrong"
* **Root cause (from code, not reproduced live):** `StoreOrderService.place()` loads the cart variants with `variants.findAllById(...)` and then reads `v.getProduct().getKind()/getStatus()/getName()`. `ProductVariant.product` is `LAZY`, `spring.jpa.open-in-view` is `false`, and `place()` is not transactional, so the session is already closed: Hibernate throws `LazyInitializationException`. `GlobalExceptionHandler` turns any unhandled exception into the generic "Something went wrong on our side" message. It happens for every order (pay-now and pay-later) before anything is saved, so no order or payment is created. The same pattern existed in the public order status view (`toDto`). The existing tests did not catch it because none call `place()`.
* **Fix:** `ProductVariantRepository.withProduct(ids)` (`join fetch v.product`), used in `place()` and the public order view.
* **Also hardened (same flow):**
  * Paystack start failure now cancels the order immediately, records a merchant-visible reason, gives the customer an actionable message (use pay-later if offered / contact the store / try again), and sends the merchant a once-a-day notification when Paystack rejected their keys. Network failures are told apart from rejected keys.
  * New 10-minute sweeper `StoreOrderService.reconcilePending()`: orders still `PENDING_PAYMENT` after 10 minutes are checked with the merchant's own Paystack (customer paid but never came back, webhook not configured); paid ones are settled through the existing idempotent `settle()`; ones Paystack never saw (1 h) or older than 24 h unpaid are cancelled, so orders are no longer left in a misleading state forever. A payment arriving after cancellation is still recorded and flagged for refund (existing behaviour).
* **Already correct (checked by reading, unchanged):** prices and totals computed server-side from the catalogue; stock and availability validated; the merchant's own Paystack secret used; payment only marked paid after the server verifies reference, amount and NGN currency with Paystack; webhook signature checked with the merchant's secret and re-verified with Paystack; `settle()` locks the order row and returns early if already paid, so duplicate webhooks/returns cannot double-process or create two sales (`store-<orderId>` idempotency key on the sale).
* **Not changed / limits:** online orders do not reserve stock while awaiting payment (a sale can still fail later for stock; that case is recorded on the order and the customer's payment is not lost). Duplicate submissions rely on the disabled button; there is no server idempotency key for creating an order.
* **Tested:** compiled with the stub checker (library-gap errors only). **Not run:** `mvn test`, a real order, a real Paystack payment. The end-to-end customer journey was **not** executed.
* **Result:** fixed in code, **requires production/staging verification** (steps in section G).

## B. Merchant Paystack
* Merchants connect in Store settings: public + secret key. `StoreSettingsService.connectPaystack` checks the key formats, refuses mixed test/live keys, **proves the secret works with a live call to Paystack** before saving, encrypts it with `SecretBox` (AES-GCM, `APP_ENCRYPTION_KEY`) and stores only the ciphertext; the API returns the public key and a connected flag, never the secret. Disconnect removes it and switches off a store with no way to pay. Both actions are audited and tenant-bound (business id from the JWT).
* Payments for a store use only that store's secret (`StoreOrderService`), with no fallback to the platform key; if a merchant has no key, online payment is refused ("Online payment is not available for this store"). Platform subscription payments use `BillingService` and the AMSATI key, a separate code path with separate webhook, untouched by these changes.
* Merchant webhook: `POST /api/webhooks/paystack/store/{slug}` (URL shown in settings), signature checked with that merchant's secret.
* Improved this round: clear customer/merchant messages and merchant notification on rejected keys, and the reconcile sweeper (so a merchant who never set the webhook still gets paid orders confirmed).
* Remaining: each merchant must set the webhook URL in their Paystack dashboard; `APP_ENCRYPTION_KEY` must be set in production.

## C. Checkout verification summary
See A.2. Verified: compile check, read-through of the payment/verification/idempotency logic. Not verified: any live order. Required before relying on it: section G.

## D. Platform administrator
See `PLATFORM_ADMIN.md`. New this round: `PlatformAdminBootstrap` (one-time, env-driven, strong password, only when no listed admin exists, never modifies existing accounts) and `PlatformAdminBootstrapTest`. Partner management APIs/screens already existed (Phase A) and are protected on every endpoint by `@partners.admin()`; merchant roles and partners get 403.

## E. Image storage
See `MEDIA_STORAGE.md`. Summary: all images are `bytea` in PostgreSQL; no external provider and no filesystem; fine for now, plan Cloudflare R2 later. Two necessary fixes applied (catalogue no longer loads blobs; replaced/removed main images no longer orphan rows).

## F. Technical change summary
* **Backend modified:** `inventory/StockService`, `restaurant/KitchenOrderService`, `catalog/ProductVariantRepository`, `store/StoreOrderService`, `store/StoreOrderRepository`, `store/ProductImageRepository`, `store/ProductImageController`, `application.yml`, `.env.example`.
* **Backend new:** `partners/PlatformAdminBootstrap`, tests `partners/PlatformAdminBootstrapTest`, docs `PLATFORM_ADMIN.md`, `MEDIA_STORAGE.md`, this file.
* **Frontend modified:** `pages/ops/Notifications.jsx`, `package.json` (`test:links`). **New:** `lib/notificationLink.js`, `scripts/notification-links-verify.mjs`.
* **Migrations:** none. **Dependencies:** none.
* **Environment variables (all optional):** `PLATFORM_ADMIN_BOOTSTRAP_EMAIL`, `PLATFORM_ADMIN_BOOTSTRAP_PASSWORD`, `PLATFORM_ADMIN_BOOTSTRAP_NAME`; tuning `STORE_RECONCILE_*` is via `app.store.reconcile-initial-delay-ms` / `reconcile-poll-ms` properties (defaults 2 min / 10 min).
* **Deployment:** redeploy backend and frontend; nothing else.
* **Tests executed:** frontend Node tests (13 partners + 3 notification-link + earlier suites) pass; Java compile check. **Not executed:** `mvn test` (including the new `PlatformAdminBootstrapTest`), vitest, `vite build`.

## G. Deployment readiness
| Item | Status |
|---|---|
| Stock notification link | Fixed and verified (route-level test; fails on old code) |
| Checkout failure (lazy-load) | Fixed in code; **requires staging/production verification** |
| Paystack init failure handling, pending-order sweeper | Fixed in code; requires verification with Paystack test keys |
| Merchant Paystack configuration | Already implemented and reviewed by reading; requires a real key test; webhook URL setup per merchant |
| Platform subscription payments unaffected | No billing code touched; not re-run live |
| Duplicate webhooks | Idempotent by design (row lock + paid check); **not tested live** |
| First platform admin | Implemented; unit tests written but **not run** |
| Partner management and authorization | Existing; admin-only on every endpoint; not run live |
| Tenant isolation | Reviewed by reading (JWT business id on uploads/orders); no isolation test run |
| Image storage audit | Established from code and migrations; **not observed on a running database** |
| Image upload/retrieval, restart persistence, cross-merchant access | Not run. Persistence follows from DB storage; stated by design, not tested |
| Merchant Paystack live key | Requires external credentials |

**Staging check for checkout (10 minutes):** set a store's Paystack test keys; open the storefront; add an item; check out Pay now -> complete the Paystack test card -> return: order shows paid, merchant sees it under Store orders and a sale exists. Repeat with Pay later. Then check out with a wrong key saved directly (or Paystack disabled) and confirm the friendly message and cancelled order. Resend the webhook from the Paystack dashboard and confirm nothing duplicates.
