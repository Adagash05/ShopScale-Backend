# ShopScale V1: scope simplification and dashboard refinement

## Switches
| Where | Setting | Default |
|---|---|---|
| Frontend (build) | `VITE_V1_SCOPE` | on |
| Backend | `V1_SCOPE` (`app.v1-scope`) | true |
| Backend | `ENABLED_BUSINESS_TYPES` | `RETAIL,WHOLESALE` (new sign-ups only) |

Setting both scope switches to `false` restores the hidden modules. Code and data are retained; no tables, columns or applied migrations were changed.

## Retained
Dashboard (with full business intelligence), sales and sales history, products and stock, CSV product import (new), receiving stock, expenses and money overview, customers and suppliers, online storefront with merchant Paystack, subscription billing (plans, prices and limits unchanged), Owner and Cashier access, sales report, settings, Help Centre.

## Hidden (backend returns 404, UI hides)
Custom domains (`/api/store/domain/**`), branches and transfers, advanced pricing, invoices and sales documents, purchase orders, cashier shifts, storefront enquiries and subscribers.
`GET /api/public/domain` stays open so storefronts already on a custom domain keep resolving.

## Removed from V1 (hidden for everyone, including existing restaurant, bakery and catering businesses)
Restaurant tables, kitchen display, modifiers, recipes, production and wastage, catering and events. Manufacturing, livestock, payroll, HR and journals were not exposed in V1 navigation.

## Navigation
Home, Sales, Products & Stock, Money, Customers & Suppliers, Online Store, Reports, Settings. Items inside each section are filtered by permission and plan.

## Dashboard (directive 2)
- `GET /api/dashboard/overview?range=today|yesterday|thisWeek|last7|last30|thisMonth|lastMonth|custom` (`DASHBOARD_VIEW`). All figures are computed on the server; the browser only formats them.
- KPI cards: Total sales, Money received, Gross profit, Business expenses, Customers owing you, Total products. Each period-based card shows a change against the previous equivalent period only when the previous period has data; otherwise it says there is not enough earlier data.
- Sales trend: area chart with tooltip; Daily view includes the previous period as a dashed line; Weekly and Monthly views are shown when the range spans more than one bucket.
- Best-selling products (ranked by revenue, with image, units, share), best customers (named customers only, linked to profiles), how customers paid (from recorded payments; unpaid credit shown separately), expenses by category, products running low, customers owing you and money you owe suppliers, recent transactions (sales, customer repayments, expenses, stock receipts).
- Money and profit fields are null for people without `DASHBOARD_FINANCIAL`, and the matching cards are not shown.
- Definitions: Total sales = completed sales after refunds. Money received = payments on those sales plus customer repayments recorded in the period. Gross profit = sales minus cost of goods sold; flagged as incomplete if sold items lack a cost price. Estimated profit after expenses is labelled an estimate. Debts and supplier balances are as of today.

## Limitations
- Credit sales have no due date in the database, so overdue balances are not computed; the age of the oldest unpaid credit is shown instead.
- The online storefront is a Growth-plan feature. Plans were left unchanged as directed.
- Cashier and role management endpoints are hidden in the UI only, not blocked on the server.
- The `/how-it-works` page no longer shows restaurant, catering or branch sections even with the scope switch off.

## Verification actually run
| Check | Result |
|---|---|
| Frontend bundle (esbuild, all of `src/main.jsx`) | 0 errors, 4 existing `import.meta` warnings |
| Node suites: domain, ga, partners, notification-links, store, theme | 9, 12, 13, 2, 17, 7 passed, 0 failed |
| `scripts/v1-verify.mjs` (sections, scope, hidden routes) | 9 passed, 0 failed |
| Help harness (SSR) | 36/36 |
| Kitchen/catering harness | 16/16 |
| Superadmin harness | all PASS lines shown, no failures |
| Chart maths (niceScale, labels, hover index, change badges) | checked with a plain Node script, all assertions held |
| AreaChart server render, trend point mapping | rendered; previous-period line present only in Daily view |
| `DashboardMath` (pure Java) | compiled and checked with plain `javac`; all behaviours correct |

## NOT run (environment has no Maven, no network installs, no browser)
- Compilation of the Spring backend, including the new overview service, the new repository queries, CSV import and `V1ScopeInterceptor`. Entity field names in them were inferred from existing code, so expect to fix small compile errors on first build.
- JUnit tests written for `V1ScopeInterceptor`, `ProductCsv` and `DashboardMath`.
- Vitest suites (including the updated `navigation.test.jsx` and the new `chartMath.test.js`).
- A real `vite build`, browser layout and mobile checks, and the Paystack end-to-end flow.

## Build and deploy
Backend: `./mvnw clean verify` first (this is the step that has not been run), then deploy as usual. Flyway migrations are unchanged, so no database step. Frontend: `npm ci && npm test && npm run build`. Optionally set `V1_SCOPE` and `ENABLED_BUSINESS_TYPES` on the server.
