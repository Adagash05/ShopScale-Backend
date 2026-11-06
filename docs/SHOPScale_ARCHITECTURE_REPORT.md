# Architecture report

* **Shape**: one Spring Boot API (Java 21, JPA, Flyway, PostgreSQL) and one React (Vite) single-page app. One shared database; every tenant table carries `business_id`.
* **Tenancy**: the JWT identifies the user and the business they selected. The tenant id is never taken from request input. A user can belong to several businesses; membership and permissions are evaluated per business (`POST /api/auth/switch`).
* **Authorization**: `AccessService` loads the active membership and role from the database (15 s cache) and answers `@access.has('PERMISSION')`. Permissions come from one catalogue (`Permission`). Escalation rules: you can only grant access you hold, cannot change your own access or the owner's, and cannot assign OWNER.
* **Integrity**: sales, refunds and purchases are single transactions. The business row is locked to serialise sales/purchases and number them without gaps; variant rows are locked to prevent overselling. `StockService` is the only code that changes stock. Client request ids make sale and purchase submissions idempotent. Prices and costs always come from the database.
* **Money rules**: see `FINANCE.md`. Credit sales are sales when made; repayments are not sales. Purchases are not expenses; cost reaches profit when goods are sold, using weighted-average cost.
* **Insights**: deterministic rules in `InsightRules` with named thresholds, each shown to the user with its basis and a link to the record to act on.
* **Subscriptions**: `SubscriptionState.compute` is the single decision point for read-only versus writable and for limits and features; `WriteGuardInterceptor` enforces it on writes; plan data lives in tables.
* **Security basics**: BCrypt, rotating refresh tokens stored as hashes with reuse detection, rate-limited credential endpoints, validation on all inputs, audit log, no secrets in code.
* **Frontend**: lazy-loaded routes, no state library, small dependency-free SVG charts for low-end phones, navigation and actions hidden by permission (the server still enforces everything).
