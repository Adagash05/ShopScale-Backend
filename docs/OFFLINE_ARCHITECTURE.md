# Offline architecture

Status: code written; **no test of the offline flow was run** (no browser, no `fake-indexeddb`, no vitest run). Syntax of the changed files was checked with the TypeScript parser. Backend V18 applied to PostgreSQL 16.
## What works offline (by design)
- The app shell (service worker `public/sw.js`, static assets only; API responses are never cached) and, with the session snapshot, reopening while offline.
- POS: product list from an IndexedDB copy (refreshed whenever online), customers from an IndexedDB copy. **Paid-in-full** sales, with or without a saved customer. Credit and part payments, new customers, refunds and everything else need a connection.
- Expenses: saved to the queue when the request fails with no connection.
## Queue (`src/lib/offlineQueue.js`, DB `shopscale-offline` v2)
Stores `sales`, `expenses` (keyPath `clientRequestId`), `catalog` (product, customer and meta snapshots). Status: PENDING, SYNCING, FAILED, SYNCED. Records are tied to `businessId`; sign-out keeps unsent records.
## Sync
`syncAll(businessId)` sends sales then expenses, oldest first, one at a time. Network error / 5xx / 429: stays PENDING, stops. 401: stays PENDING, "sign in again". Other refusals: FAILED with the server's reason, never retried silently. Runs on app open, when the connection returns, every 60 s while anything is pending, and by "Send now". Background Sync is not relied on. Progress events `shopscale:sync`; "last successful sync" stored per business. SYNCED records are purged after 7 days.
## Duplicate prevention and conflicts
- `clientRequestId` is sent with every sale/expense; the backend returns the existing record for a repeated id (sales already; expenses V18 unique partial index on `(business_id, client_request_id)`).
- Offline sales carry `expectedTotal`; if the server price differs the sale is refused with 409 and shown as FAILED (stock is checked by the normal sale path and may refuse too). The cashier then discards or retries.
## UI
Top bar: Online/Offline badge and "N unsent records" or "Sending i/n…". `More > Pending` lists sales and expenses with Try again / Discard / Send now and the last sync time.
## Not done / limits
Offline receipts are an on-screen estimate (no print); payments against invoices, purchases and stock adjustments are online-only; service worker offline reopen not tested on real devices (iOS especially); queue contents are not encrypted at rest.
