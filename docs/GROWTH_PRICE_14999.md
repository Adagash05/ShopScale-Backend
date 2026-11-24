# Growth plan price: ₦14,999 (23 Nov 2026)

- Growth monthly **₦14,999**, annual **₦149,990** (ten months of monthly, the same two-months-free pattern as before: 11,999 x 10 = 119,990). Migration `V30__growth_price_14999_keep_existing_subscribers.sql`. V29 (₦15,000) may already be applied on your database, so it is left untouched and V30 supersedes it.
- Free, Essential and Business prices and Growth's limits, features, order and "recommended" flag are unchanged.
- **Existing subscribers' billing is not changed.** Current paying Growth subscribers (at least one successful payment; active, past due, grace, cancelled-in-period or suspended) keep **₦11,999 monthly / ₦119,990 annual**. This agreed price is stored on the subscription (`locked_plan_code`, `locked_cycle`, `locked_price`) and is used for automatic renewals, manual renewals, the displayed next-renewal amount, the credit given on a plan change and the super-admin expected-revenue figure.
- It ends only when the subscriber chooses something different: a new purchase, a different plan or a different billing cycle is charged at the list price (the quote shows it before they pay). A lapsed (expired) subscriber coming back pays the list price.
- New and trial subscribers, and Growth plans given without a payment, pay ₦14,999.
- Frontend: prices are read from the API, so the pricing page, comparison, upgrade flow and checkout show the same figures; nothing is hard-coded.
- Checks: `scripts/db-checks/growth_price_migration_check.sh` (real PostgreSQL), `PricePolicy` run with `javac` (7/7), `PricePolicyTest` and `PlanSeedTest` (not run: no Maven).
- **Your call:** if you would rather move existing subscribers to ₦14,999 at a date you choose, run `UPDATE subscriptions SET locked_plan_code=NULL, locked_cycle=NULL, locked_price=NULL WHERE locked_plan_code='GROWTH';` then (and tell them first).
