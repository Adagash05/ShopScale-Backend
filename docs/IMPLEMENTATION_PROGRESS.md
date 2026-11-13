# ShopScale V1 implementation progress

Last updated: 2026-11-11. Same file in both repositories (frontend `ShopScale-Frontend`, backend `ShopScale-Backend`). Written from the actual code; nothing below is claimed unless it says it was run.

## Verification status (what was actually run)
| Check | Result |
|---|---|
| `node --test scripts/domain-verify.mjs` (hostname routing, cross-domain links, safeNext) | **9 passed** |
| `node --test scripts/theme-verify.mjs` | **4 passed** |
| `node --test scripts/ga-verify.mjs` | **12 passed** |
| TypeScript-parser syntax check of every changed frontend file | no syntax errors |
| Backend `javac` syntax-only filtered check (main and test sources) | no syntax errors (dependency errors filtered, because Maven jars are unavailable) |
| Migrations V1-V18 on a fresh PostgreSQL 16 (applied with psql), V17 constraint tests | applied; constraint tests passed |
| `InvoiceMath` logic harness, PDF structure validation | passed |
| **NOT RUN**: `mvn test`, `npm ci`, `vite build`, `vitest`, any browser, offline E2E (close/reopen, sync), Paystack, GA4 live, Cloudflare, EC2, real WhatsApp | no network / no node_modules / no browser in this environment |
**Treat every feature below as "code written, not integration-tested".** Existing vitest files (`navigation.test.jsx`, `screens.test.jsx`, `invite-and-public.test.jsx`, `api.test.js` rewritten) were not run and may need adjustment for: no redirect of authenticated users on `/` in unified mode, `SmartLink`, the new `AuthProvider`/`api.js`.

## A. Domain architecture: IMPLEMENTED, pending production verification
Hostname modes, route tables, SmartLink, `_headers`, env check, robots/sitemap. See `DOMAIN_CONFIGURATION.md`.
## B. Persistent authentication: IMPLEMENTED (backend + frontend), unrun
30-day sliding refresh cookie, rotation with 120 s grace, family revoke, sessions API, device list in Settings, offline snapshot. See `docs/SESSION_MANAGEMENT.md`.
## C. Dark mode: IMPLEMENTED, not visually verified
Light/Dark/System in Settings, token CSS. Preference is per device, not per account. See `docs/DARK_MODE.md`.
## D. Customer invoicing: PARTIAL
Done: schema, math, ledger reconciliation, PDF, public link, payments, list/create/detail UI, share (native + wa.me), "Create invoice" from sales history.
Missing: **UI to edit invoice settings (business details, bank details, logo)**; invoice from an online-store order (backend supports order source? not verified in UI); email delivery; invoice edit after payment. See `docs/INVOICING_AND_WHATSAPP.md`.
## E. Offline operations: PARTIAL (code complete for the scope below, unrun)
Done: IndexedDB v2 (sales, expenses, catalogue, customers, last-sync), offline POS for paid-in-full sales incl. saved customers, offline expenses, idempotency (sales, expenses), `expectedTotal` conflict check, sync engine with progress and last-sync, status badge, pending page with retry/discard. See `docs/OFFLINE_ARCHITECTURE.md`.
Not done: offline receipts printing, offline payments on credit/part payments, offline purchases/stock, encryption at rest, service-worker offline reopen test, unit tests for the queue (no `fake-indexeddb`).
## F. Integration: NOT VERIFIED
No integration, build or backend test execution was possible.

## G. Billing repair and automatic renewal (session 2, 2026-11-11): IMPLEMENTED, NOT EXECUTED END-TO-END
Full root-cause, change and test report: `docs/BILLING.md`. Summary: migration V19; `RenewalService` (SKIP LOCKED + unique attempt rows), authorization capture with consent, cancel/resume/auto-renew endpoints, startup config validator, `settle` hardening (abandoned status, customer/metadata checks, transaction id), webhook over raw bytes, `Plan.jsx` reference recovery + consent + renewal panel.
Run: migrations V1-V19 on PostgreSQL 16, V19 over existing data, constraint tests, concurrent SKIP LOCKED claim (3 + 1, no overlap), 14 date/config assertions in a JDK harness, syntax checks. Not run: Maven build and JUnit, Hibernate startup validation, any Paystack sandbox call, vitest (`plan.test.jsx`), merchant storefront regression.
Next billing task: on a networked machine run `mvn test`, fix compile/mapping errors, then the Paystack test-mode walkthrough listed under "First actions" in `docs/BILLING.md`.

## Files modified / added
Frontend added: `src/config/{domains,env}.js`, `src/lib/{SmartLink.jsx,sessionCache.js,invoiceShare.js}`, `src/theme/*`, `src/modules/invoices`, `src/pages/invoices/*`, `src/pages/DeviceSessions.jsx`, `scripts/{check-env,domain-verify,theme-verify}.mjs`, `public/_headers`, docs.
Frontend modified: `App.jsx`, `main.jsx`, `lib/api.js`, `lib/offlineQueue.js`, `lib/useOfflineSync.js`, `auth/AuthContext.jsx`, `marketing/*`, `pages/{Login,Register,AccountLinks,AcceptInvite,Pricing,Layout,Settings,Onboarding,Customers,SalesHistory,Sell,Expenses,PendingSales,TopBar,Insights}.jsx`, `pages/ops/Export.jsx`, `pages/finance/{Accounts,Overview}.jsx`, `lib/permissions.js`, `styles.css`, `charts.jsx`, `index.html`, `vite.config.js`, `package.json`, `.env.example`, `Dockerfile`, `public/{robots.txt,sitemap.xml,manifest.webmanifest}`, `src/test/api.test.js`.
Backend added: migrations V16, V17, V18; `auth/RefreshCookies`, `auth/RefreshCookieAdvice`; package `invoice/*`; tests `DeviceLabelTest`, `InvoiceMathTest`, `InvoicePdfTest`.
Backend modified: `auth/{RefreshToken,RefreshTokenRepository,AuthService,AuthController,AuthResponse}`, `security/{SecurityConfig,Permission,DefaultRoles}`, `expense/{Expense,ExpenseRepository,ExpenseService}`, `sales/SaleService`, `application.yml`, `PermissionAnnotationsTest`, `.env.example`.

## Migrations
V16 `sessions` (refresh token families, rotated_at, device); V17 `invoices` (invoices, items, payments, settings, counters); V18 `offline_idempotency` (expenses.client_request_id + unique partial index). Existing migrations unchanged.

## API changes
New: `/api/auth/sessions` (GET), `/api/auth/sessions/{id}` (DELETE), `/api/auth/logout-all`; `/api/invoices/**`; `/api/public/invoices/{token}/pdf`.
Changed: `/api/auth/login|register|refresh|logout` now set/clear the HttpOnly `ss_rt` cookie; refresh and logout require `X-ShopScale-Client: web` + allowed Origin; the refresh token is moved out of the JSON body into the cookie by `RefreshCookieAdvice` (so non-browser clients cannot read it from the body). `POST /api/sales` accepts optional `expectedTotal` (409 on mismatch). `POST /api/expenses` accepts optional `clientRequestId` (idempotent).
Backend env additions: `COOKIE_SECURE`, `COOKIE_SAMESITE`, `COOKIE_DOMAIN`; `CORS_ALLOWED_ORIGINS` must list the three web origins.

## Known issues / blockers
- No build or test run is possible here; first action on a machine with network: `npm ci && npm test && npm run build`, `mvn test`.
- `PermissionAnnotationsTest` open-handler list was edited by hand and must be confirmed by running it.
- Invoice settings screen missing (see D).
- Offline queue has no automated test.

## Exact next task
0. Billing: see section G.
1. On a networked machine: `mvn -q test` and `npm ci && npm test && npm run build`; fix whatever fails (likely candidates: the three vitest files listed above, `PermissionAnnotationsTest`, JPA queries in `InvoiceRepositories`).
2. Build the invoice settings screen (`GET/PUT /api/invoices/settings`, logo upload) in Settings or under Invoices.
3. Add `fake-indexeddb` and vitest tests for `offlineQueue.js` (enqueue, syncAll with mocked `api`, FAILED vs PENDING classification, idempotent resend).
4. Production verification list in `DOMAIN_CONFIGURATION.md`.


## H. Premium storefront (latest)
Full report: `docs/STOREFRONT.md`. The public store at `/s/:slug` was rebuilt as a multi-page site (`src/store/*`): announcement bar, sticky header, mobile drawer, home, shop (search/category/sort), product pages with variants, cart, checkout, My orders (device-based), info pages, 4-column footer; centrally controlled CSS, business-type presentation (grid vs menu). Backend: `StoreOrderService` additive fields (businessType, subtype, email, address, productId, categoryId/Name, createdAt).
Run: `store-verify.mjs` 11/11; esbuild bundle; 30 Chromium renders clean; scripted journey 56/56 on two mock stores (desktop + mobile). Not run: mvn, vitest, vite build, real Paystack, real backend, real react-router/lucide.
Known gaps: no logo, sale price, gallery, specs, reviews, social links, newsletter or customer accounts (no data model); email/address now public.
Next: run the networked checks listed in `docs/STOREFRONT.md` section 8.


## I. Storefront second pass (deferred features)
Full report: `docs/STOREFRONT.md` section 9. Added: logo, social links, opening hours, sale prices, extra photos, specifications, verified-purchase reviews with moderation, newsletter, catering enquiries, order lookup by phone + number, and restaurant menu options (server priced, stock and cost handled). Migration V20; new `StoreExtrasService/Controller`; merchant pages Store inbox, store profile panel, product "Online store details".
Run: store-verify 17/17; storefront journeys 90/90 + 56/56 on a mock API (desktop + mobile); backend parse check only (267 files). NOT run: mvn, vitest, vite build, the V20 migration, real Paystack, merchant dashboard screens (written, never rendered).
Next: `mvn test`, apply V20 on a copy of real data, place a real option order and check the sale total, ingredient stock and cost.

## K. Registration, Resend and light default (2026-11-12)
See `docs/EMAIL_AND_REGISTRATION.md`. Verified in the build sandbox: migrations V1-V22 on PostgreSQL 16, the lock-timeout reproduction and fix, atomic token consumption, the real `EmailService` retry/after-commit/non-blocking logic (run against stand-in mail classes), and the theme and storefront Node tests. NOT run here: `mvn test`, `vitest`, `vite build`, a real Resend send.
