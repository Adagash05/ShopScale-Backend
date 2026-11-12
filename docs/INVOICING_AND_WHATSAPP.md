# Customer invoicing and WhatsApp sharing

Status: implemented. Run and verified: migration V17 (including its constraint checks), the `InvoiceMath` logic in a harness, and PDF structure validation. **Not run:** JUnit (`InvoiceMathTest`, `InvoicePdfTest`), vitest, end-to-end UI, real WhatsApp.

## Backend (`invoice/` package, V17)
- Invoices (from a sale, from an order, or standalone), items, payments, per-business number counter, per-business settings (name, address, bank details, footer, logo).
- All amounts are computed server-side by `InvoiceMath`; tenant id comes from the JWT.
- Permissions: `CUSTOMER_INVOICES_VIEW`, `CUSTOMER_INVOICES_MANAGE`, `CUSTOMER_INVOICES_PAYMENT_RECORD` (not the existing `INVOICES_VIEW`, which is for ShopScale subscription invoices).
- Endpoints: `/api/invoices` (list/create), `/{id}`, `PUT /{id}`, `/{id}/sent`, `/{id}/cancel`, `/{id}/payments`, `/{id}/pdf`, `/settings` (+ `/settings/logo`), public `GET /api/public/invoices/{token}/pdf` (unguessable token).
- PDF: hand-written, dependency-free writer (`SimplePdf`, Helvetica). The naira sign is not in the standard font, so amounts print as "NGN".
## Ledger reconciliation (no duplicate income)
- Invoice created from a sale: money already collected by the sale is **not** posted again; only payments recorded later on the invoice post once, and the customer balance falls.
- Standalone invoice: posts income only when a payment is recorded.
- An invoice that has payments recorded cannot be cancelled (the API refuses with 409), so cancelling never needs to reverse ledger entries.
## WhatsApp
`src/lib/invoiceShare.js`: uses `navigator.share` with the PDF file where the browser supports it; otherwise opens `wa.me/<number>?text=` with the public PDF link. wa.me **cannot attach a file or confirm that the message was sent**, so "Mark as sent" is a manual action.
## Frontend
`src/pages/invoices/` (list, form, detail) and "Create invoice" on sales history. **There is no screen yet for editing the invoice settings (business name/address/bank details/logo):** the API (`/api/invoices/settings`) exists and the form reads it, but nothing in the UI writes it. This is an open task.
## Known limits
No invoice editing after payments; no tax rules beyond a flat rate on the form; no email sending.
