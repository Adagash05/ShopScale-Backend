# ShopScale: implementation status

Updated at the end of development cycle 3. Source of truth is the code in the two archives.

## How to read the state column
* **BUILT, UNVERIFIED**: implemented end to end in code (screen, API, database), but the backend has never been compiled or run because Maven Central was unreachable in the authoring environment. Nothing in this state is proven to work.
* **FRONTEND-VERIFIED**: the screen logic is covered by passing automated tests, but it has not been exercised against a live backend.
* **MISSING**: not built. **BLOCKED**: needs credentials or infrastructure that were not available.
* Nothing is marked COMPLETE: that requires an end-to-end test against a running backend and database, and none has been run.

## Completion matrix
| Area | State | Evidence in the code | Gaps |
|---|---|---|---|
| Register, login, logout, refresh rotation, reuse detection | BUILT, UNVERIFIED | `auth/AuthService`, `RefreshToken`, `JwtService` (JWT round-trip logic has JUnit tests, not run) | Email verification and password reset MISSING (need an email provider) |
| Multi-tenancy | BUILT, UNVERIFIED | Tenant comes only from the JWT (`security/Tenant`); every repository query filters on `business_id` | No automated cross-tenant test exists. This is the highest-risk unproven area |
| Business onboarding, store slug | BUILT, UNVERIFIED | `BusinessService`, `SlugRules` (logic verified by compiled harness); `Onboarding.jsx` FRONTEND-VERIFIED | Retail only; food types intentionally disabled |
| Roles and permissions (RBAC) | BUILT, UNVERIFIED | `Permission` (76 permissions), `DefaultRoles` (7 roles), `AccessService`, `@PreAuthorize("@access.has(...)")` on every business endpoint. Role defaults and escalation rule **verified by compiled harness** | `PermissionAnnotationsTest` (checks every endpoint is protected and names real permissions) written, not run. Location-level access MISSING (no locations model) |
| Staff invitations and team screens | BUILT, UNVERIFIED | `TeamService`, `InvitationService`, `Team.jsx`, `AcceptInvite.jsx` (accept screen FRONTEND-VERIFIED) | Email sends only if SMTP is configured (BLOCKED on credentials); otherwise the inviter copies the link. Ownership transfer MISSING |
| Custom roles | BUILT, UNVERIFIED | `CustomRole`, `RoleEditor`; you can only grant permissions you hold | Default roles are fixed in code; to customise one, copy it as a custom role |
| Products, variants, categories, archive | BUILT, UNVERIFIED | `catalog/*`, `Products.jsx`, `ProductForm.jsx` | No image upload (field exists, no uploader). CSV import/export MISSING |
| Inventory, stock movements, low stock | BUILT, UNVERIFIED | `StockService` is the single writer; `InventoryService` | Stock transfers between locations MISSING |
| POS sales (atomic, idempotent), refunds, receipts | BUILT, UNVERIFIED | `SaleService.create/refund`; cart maths FRONTEND-VERIFIED | Whole-sale refunds only. Card/Paystack payments MISSING (card is a manual record) |
| Customers, credit, repayments | BUILT, UNVERIFIED | `customer/*` | |
| Suppliers, purchases, receiving stock, supplier payments | BUILT, UNVERIFIED | `purchasing/*`, `NewPurchase.jsx`. Weighted-average cost maths **verified by compiled harness** | Purchase cancellation and returns MISSING (correct with a stock adjustment). Supplier balances need Growth plan or higher |
| Expenses | BUILT, UNVERIFIED | `expense/*` | No receipt attachments |
| Dashboard and charts | BUILT, UNVERIFIED | `DashboardService`; charts FRONTEND-VERIFIED | Figures are hidden from people without `DASHBOARD_FINANCIAL`. Pending orders/order-status charts MISSING (no orders module) |
| Insights ("what needs attention") | BUILT, UNVERIFIED | `InsightService`; all thresholds in `InsightRules` (**verified by compiled harness**); panel FRONTEND-VERIFIED | Needs real data to judge usefulness: see pilot validation below |
| Activity feed | BUILT, UNVERIFIED | Reads real `audit_logs` rows | Only events that write audit records appear (sales, stock, expenses, customers, team, purchases, products) |
| Subscription state, trial, plan limits, read-only after expiry | BUILT, UNVERIFIED | `SubscriptionState` (JUnit tests written, not run) | See next row |
| Paystack for subscriptions (pay, verify, webhooks, renew, upgrade, downgrade, cancel, promo codes) | MISSING | None | Plans can only be changed in the database today. Needs your Paystack keys to test |
| Merchant Paystack and storefront payments | MISSING | None | BLOCKED on keys for testing |
| Online storefront, store settings, online orders | MISSING | None (the slug is reserved) | Largest remaining product gap |
| Restaurant, kitchen, tables, ingredients, recipes | MISSING | Permissions exist in the catalogue; no screens or APIs | |
| Catering (advance orders, deposits, balances) | MISSING | None | |
| Delivery and pickup | MISSING | None | |
| Custom domains | MISSING | None | Needs DNS and SSL provider |
| Super admin | MISSING | None | |
| Notifications (in-app, email) | MISSING | Only invitation email exists | |
| Help centre | FRONTEND-VERIFIED (static) | `Help.jsx` | Content covers only what exists today |
| Public website (home, features, shops, restaurants, catering, pricing, about, contact, FAQ, privacy, terms, refunds, 404) | FRONTEND-VERIFIED | `marketing/*`, sitemap, robots, per-page SEO tags (tests passing) | Product visuals are labelled illustrations with sample figures, not real screenshots. Legal pages are drafts needing legal review. Contact page shows no details until `VITE_SUPPORT_EMAIL` / `VITE_WHATSAPP_NUMBER` are set. Restaurant/catering pages say "coming soon" |
| Offline POS | MISSING | Documented as a later feature | |

## Business value check (what an owner gets today)
* Selling: fast sale, credit and part payments, receipt, refund.
* Stock: always correct after sales and purchases; running-low and stock-out-while-selling warnings.
* Money: sales, money received, expenses, estimated profit with its working shown, who owes you, what you owe suppliers.
* Accountability: every important action is audit-logged with the person; staff only see what their role allows.
* Costs stay accurate because receiving stock updates each product's average cost.

## Pilot validation still needed (cannot be judged from code)
1. Whether the insight thresholds (7 days of cover, 30-day window, 10% margin, 14 days overdue, 20%/30% period changes) suit real shops.
2. Whether shop owners understand the Sell, Receive stock and Team flows without help.
3. Real-device behaviour on low-end Android phones and weak networks.

## Recommended next step
Run `mvn -B verify` and a first boot against PostgreSQL, then fix what surfaces. Until the backend has compiled and run once, everything built on it carries unknown risk; adding the storefront or Paystack on top would compound that. After that: tenant-isolation and concurrent-sale integration tests, then Paystack subscriptions.
