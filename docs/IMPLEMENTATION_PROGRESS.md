# ShopScale V1 — implementation progress (baseline: ShopScale-Backend (2) / ShopScale-Frontend (2))

Status legend: DONE (code written, statically reviewed) · NOT RUN = nothing here has been compiled or executed in the authoring
environment (no Maven/npm access). Run `mvn test` and `npm test` locally.

## Backend (migrations V5–V7 verified on PostgreSQL 16; Java only parse-checked with javac, NOT compiled/run)
| Area | Status |
|---|---|
| V5 launch pricing, branches, branch_stock, decimal quantities, units, transfers | DONE |
| Branches API, plan location limit, branch-aware stock (`StockService`), unit conversions, stock transfers | DONE |
| Sales / purchases / inventory adjust on decimals and branches; POS item list per branch | DONE |
| Permissions added + roles renamed (Cashier, Storekeeper, Restaurant/kitchen staff); business types Wholesale/Catering/Production/Livestock | DONE |
| Workflows + IndustryGuard; auth response carries features/workflows/plan | DONE |
| V6 purchase orders with partial receiving (`/api/purchase-orders`) | DONE |
| V7 finance: cash/bank accounts, money ledger (posted by sales, refunds, customer payments, expenses, purchases, supplier payments), expense approvals, cashier shifts + reconciliation, summary/debtors/payables | DONE |

## Frontend
Module registry wired into App/More/Layout; Locations, Stock transfers, Supplier orders, Cash and bank, Money overview, Cashier shifts pages; decimal quantities + units in product form, sell, stock adjust, purchase; expense approval UI. Not built or run (no npm); JSX syntax-checked only.

## Outstanding (in order)
POS partial refunds/voids/quotes/sales orders/reports · wholesale tiers/customer types · notifications · restaurant/kitchen/recipes/wastage · production ·
catering · storefront + merchant Paystack · AMSATI subscription billing + new pricing page · CSV import/export · images · password reset/email verification ·
offline POS · industry onboarding/nav/dashboards (Livestock choice in onboarding UI) · settings UI for expense approval limit · marketing copy · docs · tests · final ZIPs.

## Update — POS returns, quotes/orders, sales reports (V8)
- DONE (backend, parse-checked only; not compiled): V8 migration (verified applying on PostgreSQL 16), partial/full refunds, voids, quotations, customer orders with deposits, `/api/sales/report`.
- DONE (frontend, syntax-checked only; not built): SalesHistory return/void modals, `pages/pos/Documents.jsx` (Quotes/Orders), `pages/pos/SalesReport.jsx`, `modules/pos/index.js` (routes more/quotes, more/orders-in, more/sales-report). DTO field names cross-checked against SalesDocumentService / SalesReportService.
- Known limitation: dashboard payment-method breakdown does not subtract partial refunds.
- NEXT: wholesale tiered pricing + customer types, then notifications, restaurant, production, catering, storefront + merchant Paystack, subscription billing, CSV, images, password reset/email verification, offline POS, industry onboarding/nav, docs, tests update, ZIP delivery.

## STATUS AT DELIVERY — V1 IS NOT COMPLETE (intermediate delivery requested by the user)
Verification: migrations V1–V8 applied on PostgreSQL 16 (executed). Java: parse-only javac check (no compile, symbols unresolved). Frontend: JSX syntax check only. NO unit tests, NO Maven build, NO npm build were run. Existing tests were not updated for changed signatures (decimal quantities, SubscriptionState, ItemDto/SaleDto) and will likely need fixes.

IMPLEMENTED (unverified beyond the above): branches + branch stock, decimal quantities/units/conversions, stock transfers, suppliers/purchase orders/partial receiving, cash & bank accounts + ledger, expenses with approvals, cashier shifts, refunds/voids, quotations, customer orders with deposits, sales reports, granular permissions/six default roles, launch pricing plans (V5), industry workflow guard, Livestock industry persisted.

NOT IMPLEMENTED (outstanding): wholesale tiered pricing/customer types; notifications; restaurant (menu, modifiers, tables, kitchen, recipes, wastage); production/recipes; catering; online storefront + merchant Paystack; AMSATI subscription billing (Paystack verify/webhooks/renew/upgrade/downgrade/cancel) and pricing-page copy; CSV import/export; product images; password reset + email verification; offline POS (PWA/IndexedDB/sync); employee invitation + activity-history UI; industry-specific onboarding/nav/dashboards; domain/CORS/redirect config; settings UI for expense approval limit; README/env/deployment/API docs; test updates.

EXACT NEXT TASK: wholesale tiered pricing (price_tiers + customers.customer_type, migration V9, SaleService price resolution server-side, UI in ProductForm/Customers), then notifications, then restaurant.
