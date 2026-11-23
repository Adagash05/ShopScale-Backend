# Invoicing, debt reminders, Ask ShopScale, plan audit and app domain (Nov 2026)

## What ran and what did not
**Ran here (real results):** esbuild bundle of the whole frontend, 0 errors; node:test suites `v1-verify` 10/10, `pwa-verify` 8/8, `domain-verify` 9/9, `reminders-verify` 6/6, `store-verify` 17/17, `partners-verify` 13/13, `ga-verify` 12/12, `theme-verify` 7/7, `notification-links-verify` 2/2; SSR harness (Ask, Help, Remind customer) 8/8; `AskText` via plain `javac` 13/13.
**Never ran (no Maven, no browser, no network here):** every Spring class, every JUnit test (including the new `InvoiceReportingTest`, `AskServiceTest`), Vitest, `vite build`, Flyway V27/V28, PWA install on the real domain, Paystack. Treat the backend as written but uncompiled: run `mvn test` first.

## Invoicing (existing implementation reused, not replaced)
- Invoices are no longer hidden in V1 (backend interceptor and frontend routes). Invoices are not plan-gated; no entitlement was removed or added.
- Sales > **Invoices** tab (needs `CUSTOMER_INVOICES_VIEW`).
- New: product picker on the invoice form. A product line sends only `variantId`; the server takes the name and price from the catalogue of the signed-in business and ignores any price from the browser. Free-text lines still work.
- New: duplicate-creation protection. The form sends a `clientRequestId`; the server returns the existing invoice for the same business and id (V28 adds the column and a partial unique index).
- Unchanged rules: totals from `InvoiceMath`; Overdue is derived from the due date, never stored; an issued invoice is not money received; payments post once; a sale-backed invoice starts with what the sale collected; an order-backed invoice is a document only.

## Reporting, so nothing is counted twice
- Sales revenue: completed sales only (unchanged).
- Money received: sale payments + customer repayments + **payments recorded on standalone invoices**.
- Customers owing you: credit balances (unchanged; a sale-backed invoice is already in here).
- **Unpaid invoices / Overdue invoices** (new): sent, not fully paid, standalone invoices only. Shown on Home "Money owed" and in Ask "Which customers owe me money?".

## WhatsApp
Share invoice (existing): native file share where the device supports it, otherwise wa.me with the customer's number and a message plus secure link. No delivery claim; the user confirms before "Mark as sent". No WhatsApp Business API.

## Remind customer (new)
Available on an unpaid invoice, and on a customer who owes money. Three editable templates (friendly, due, overdue); the suggestion follows the due date. Uses the real customer name, business name, balance, invoice number and due date, and leaves out any detail that does not exist. Copy, or open WhatsApp with the saved number and the edited text. Nothing is sent automatically. Not added to the invoice list rows (a button inside a link row would be invalid markup); it is on the invoice and the customer.

## Help
Ninth category "Invoices and payments" (4 answers). No invented channels.

## Plan availability audit (findings)
| Feature | Advertised | Configured | Enforced | Status | Recommendation |
|---|---|---|---|---|---|
| STOREFRONT (online store) | Growth and above | Growth and above | Yes | Working; in-app Help wrongly said Essential | Help text corrected |
| ASK_SHOPSCALE | New | Essential, Growth, Business (V27) | Yes, backend `requireFeature` | New, untested on a database | **Owner to confirm tier** |
| Invoices | Not listed as a plan feature | None | Permission only | Working in code, untested | Keep ungated unless you decide otherwise |
| STAFF_PERMISSIONS | Advertised | Configured | Not delivered as described | Mismatch | Hidden from pricing until delivered |
| PRIORITY_SUPPORT | Advertised | Configured | Not delivered | Mismatch | Hidden from pricing until delivered |
| ACTIVITY_HISTORY | Business only (on paper) | Configured | Not enforced | Mismatch | **Owner decision**: enforce, or stop advertising |
Prices and limits were not changed. Only these six rows were examined in this report; the earlier audit covered the rest and is in the earlier report.

## Domain and PWA
App and PWA: `https://app.shopscale.com.ng`. Marketing and registration: `https://shopscale.com.ng`, which never registers a service worker or manifest (`retirePwa()` also removes a worker or caches left from earlier visits). Service worker ignores `/api/` and cross-origin requests and caches static files only; updates wait for the user; the offline queue is cleared on logout; an offline sale shows "Not confirmed yet". Production values are in `.env.production.example` (frontend and backend), kept apart from local `.env`. No DNS or infrastructure was touched. Not verified on the live domain: manifest fetch, service-worker scope, install and launch need a check in a real browser after deploy.

## Known limitations
- Credit sales have no due date, so their reminders use the friendly template.
- Remind button is not on the invoice list rows.
- Reminders are not logged.
- Money-owed total and the unpaid-invoice total are separate on purpose.

## Needs your approval or action
1. ASK_SHOPSCALE tier. 2. ACTIVITY_HISTORY: enforce or stop advertising. 3. Migration numbers V27 and V28 may collide with local migrations you have; rename before running if so. 4. Rotate any secrets that were in the earlier zip that contained `.env`. 5. Run `mvn test` and a browser pass.

## Files (main)
Backend: V27, V28; `ask/*`; `invoice/InvoiceService, Invoice, InvoiceRepositories, InvoiceReporting`; `dashboard/DashboardService, DashboardOverviewService`; `V1ScopeInterceptor`; tests. Frontend: `lib/reminders.js`, `pages/invoices/RemindCustomer.jsx, InvoiceForm.jsx, InvoiceDetail.jsx`, `pages/CustomerDetail.jsx, Home.jsx, helpContent.js`, `config/scope.js, sections.js`, `scripts/reminders-verify.mjs`, `scripts/ask-harness/*`.
