# Kitchen, Bakery and Catering (migration V26)

Bakery lives inside the Kitchen/Restaurant module. It is not a separate manufacturing system.

## Kitchen / restaurant
- **Menu**: products of kind MENU with variations (sizes), paid modifier groups, sold-out switch (`products.sold_out`, enforced in `SaleService`), archive, recipe link to ingredients.
- **Orders**: dine-in (table required), takeaway, other. Create is idempotent (`client_request_id`). Items can be added/edited/removed only while NEW. Order stage is derived from item statuses (PENDING, ACCEPTED, PREPARING, READY, SERVED, CANCELLED); it is never stored separately.
- **Kitchen board**: `GET /api/restaurant/kitchen`, polled every 10 s. Repeating a status update is a no-op. Item timestamps are recorded.
- **Payment**: through `SaleService.createInternal` (channel RESTAURANT, key `ko-<orderId>`), so split payments, paid/part/unpaid, finance posting and ingredient stock deduction follow the existing sale path and happen once.
- **Cancel**: before cooking, free. After cooking started, reason and SALES_VOID are required; optional "wasted" records ingredients as wastage (idempotent key `ko-item-<id>`).
- **Reverse a paid order**: SALES_VOID, uses `SaleReturnService.voidSale`. Optional restock only for items never started. Cooked food is not restored.
- **Wastage**: `/api/wastage` with reasons; a made-to-order dish consumes its ingredients as WASTAGE.
- **Production (bakery)**: `/api/production/runs` consumes the recipe, adds finished stock, averages cost; idempotent.
- **Reports**: `/api/restaurant/reports` (sales by item, order volume, ingredient use).
- **Custom cake orders**: existing Customer orders screen (deposit, balance, delivery date).

## Catering
`/api/catering/*`. Status machine ENQUIRY -> QUOTED -> BOOKED -> IN_PREPARATION -> READY -> COMPLETED, CANCELLED from any open state.
- Prices are always computed on the server (package per guest with minimum guests, fixed price, services, discount); the client preview is advisory.
- Confirm requires the deposit and checks venue/date conflicts.
- Payments: idempotent, never above the balance, posted to the finance ledger once. Revenue is read from `catering_payments`; catering payments are not Sales, so nothing is counted twice.
- Preparation: checklist, ingredient plan with on-hand check. Stock is issued once per event, guarded by `UPDATE ... WHERE stock_issued_at IS NULL`. Extra/returned/wasted usage is recorded afterwards.
- Costs: estimates are plans only; actual costs go through `ExpenseService`. Profit = revenue - actual cost.
- Users without CATERING_MANAGE receive no money fields.

## Known design limits
- Restaurant stock is deducted when the order is paid, not at accept. Accepting runs an availability check.
- Catering balances are per event, not customer-account credit.
- No public catering enquiry form; the storefront link to `/enquiry` predates this work and was not changed.

## Verification
See `docs/IMPLEMENTATION_PROGRESS.md` and the final report for what was and was not executed. `scripts/kitchen-catering-constraints.sql` checks the database guards.
