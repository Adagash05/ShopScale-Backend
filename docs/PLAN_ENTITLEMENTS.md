# Plan entitlements (V31)

One source of truth: the `plans` table (limits) and `plan_features` (features), read by `SubscriptionState`. The frontend reads the same data from `GET /api/subscriptions/plans` and `/current`; nothing about plans is hard-coded in it.

## Configuration
| Entitlement | Free | Essential | Growth | Business |
|---|---:|---:|---:|---:|
| users (`max_users`) | 1 | 2 | 5 | 10 |
| active products (`max_items`) | 10 | 50 | 200 | unlimited |
| customers (`max_customers`) | 10 | 50 | unlimited | unlimited |
| expenses (`max_expenses`, `expense_period`) | 10 in total | 50 per billing month | unlimited | unlimited |
| suppliers (`max_suppliers`) | 0 | 10 | 50 | unlimited |
| WhatsApp reminders per billing month (`max_whatsapp_reminders`) | 0 | 20 | 50 | unlimited |
| `WHATSAPP_REMINDERS` | no | yes | yes | yes |
| `DATA_EXPORT`, `ASK_SHOPSCALE`, `BEST_SELLING_PRODUCTS`, `BEST_SELLING_CUSTOMERS`, `ACTIVITY_HISTORY`, `STOREFRONT`, `ONLINE_ORDERS` | no | no | yes | yes |

NULL means unlimited. Locations, prices and every other feature are as before.

## Where each one is enforced (server)
| Entitlement | Enforced in |
|---|---|
| products (active only; archived do not count) | `ProductService.create`, `ProductService.restore` (so CSV import too) |
| customers | `CustomerService.create` |
| suppliers | `PurchasingService.createSupplier` |
| expenses | `ExpenseService.create` (Free: all expenses; Essential: expenses created in the current billing month) |
| users | `TeamService.invite`, `TeamService.setActive`, `InvitationService.accept` |
| WhatsApp reminders | `POST /api/reminders`, `GET /api/reminders/usage` (`ReminderService`) |
| CSV export | `GET /api/export/{what}.csv` (the only export endpoint for business data) |
| Ask ShopScale | `GET /api/ask/questions`, `GET /api/ask/{key}` |
| Best-selling products / customers | `GET /api/dashboard` and `GET /api/dashboard/overview`: on other plans the queries are not run, the lists are empty and `bestProductsAvailable` / `bestCustomersAvailable` are false |
| Activity history | `GET /api/activity/history`, `GET /api/activity` |
| Online order management | all merchant `/api/store/orders*` endpoints (`StoreOrderService`) |

## Atomic limits
Every create path calls `SubscriptionService.lockForCreate(businessId, resource)` first. It takes a PostgreSQL transaction advisory lock for that business and resource, held until the creating transaction ends, so the count and the insert cannot interleave with another request. It must run inside the creating `@Transactional` method (all of them are).

## Billing month
Monthly allowances (Essential expenses, reminders) use `UsagePeriod.billingMonth(periodStart, now)`: windows of one month anchored on the start of the current subscription period, so a yearly plan still has twelve monthly windows, and logging out or restarting changes nothing. A new payment that starts a new period starts a new window. The permanent Free plan has no reminders and counts expenses in total.

## WhatsApp reminders: what is counted
ShopScale does not send WhatsApp messages; the person opens WhatsApp or copies the text. The app asks the server to count a reminder first (`POST /api/reminders` with a `requestId`). Only when the server accepts does WhatsApp open or the text copy. A rejected request (plan, allowance used up, wrong business) uses nothing and opens nothing. One `requestId` is counted once, so a double tap, retry, or copy followed by open uses one unit. Business is never blocked. The server cannot see whether the person finally pressed send in WhatsApp; the count is reminders handed to WhatsApp or copied.

## Errors
Plan refusals are HTTP 402 with `code` (`PLAN_LIMIT_REACHED`, `FEATURE_NOT_IN_PLAN`, `REMINDER_LIMIT_REACHED`) and `details` (`resource`, `limit`, `plan`, and for reminders `used` and `resetsAt`). The frontend shows an upgrade banner for any such 402.

## Downgrades and existing data
V31 changes plan rows only. No product, customer, expense, supplier or subscription is changed or deleted. A business above a new limit keeps everything and cannot add more until it is back within the allowance (the check is "records after this one would exceed the limit"). Downgrading to a plan with fewer users or locations is still refused until the extra people or branches are deactivated (existing `requireFits` rule); this deletes nothing.

## Check
`PSQL="psql -h /tmp/pg -p 5544 -U postgres" scripts/db-checks/entitlements_check.sh` applies V1 to V31 on PostgreSQL, checks the matrix, that data and prices are unchanged, and races concurrent creators and reminders against the allowances.
