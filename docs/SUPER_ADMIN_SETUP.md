# Super Admin setup

The Super Admin is the platform owner role. It is independent of merchant roles: an OWNER, ADMIN, MANAGER or CASHIER of any business never has it, and it cannot be granted from the app, from an API or from a database role column a merchant can reach. It exists only on an account created by the one-time bootstrap below.

## Create the account (once, manual)
1. Choose an email address that is **not** registered in ShopScale, and a strong temporary password (12+ characters, upper, lower, digit, symbol).
2. Set on the server:
   ```
   SHOPSCALE_SUPER_ADMIN_EMAIL=owner@yourdomain.com
   SHOPSCALE_SUPER_ADMIN_INITIAL_PASSWORD=<temporary password>
   ```
3. Deploy / restart the API once. Either it provisions at start-up, or run it as a command and let it exit:
   ```
   java -jar shopscale.jar --provision-super-admin
   ```
   The log says `Super Admin account created` (it never prints the password). Exit code 0 means created or already existing.
4. **Remove both variables** from the environment and restart.
5. Open `https://app.shopscale.com.ng/super-admin/login`, sign in, and you are asked to set a new password (12+ characters) before anything else works.

## What the bootstrap refuses
* a weak password, an invalid email, only one of the two variables;
* an email that already belongs to a registered account (nobody is promoted, no password is overwritten);
* creating a second Super Admin when one exists (it is serialised with a database lock, so two instances cannot both create one).

## Lost access
There is deliberately no "forgot password" and no admin-reset screen. If the owner loses the password, a person with **database and server access** must act: delete the Super Admin user row and its `admin_sessions`, then repeat the bootstrap (the audit log keeps the history). Take a database backup first.

## Pages
`/super-admin/login`, `/dashboard`, `/businesses`, `/users`, `/partners`, `/subscriptions`, `/transactions`, `/support`, `/announcements`, `/audit-logs`, `/storage`, `/settings`, `/account`. Merchants who open these see only the login screen; every API behind them checks the role on the server.

## Day to day
* **Businesses:** search, filter, open a profile (team, branches, subscription, payments, tickets, activity, administrative history), edit profile fields, **suspend / reactivate** (always with a written reason; nothing is deleted; the merchant is blocked within 15 seconds and the storefront closes).
* **Users:** suspend / reactivate; send the user the normal password-reset email (you never see or set their password).
* **Partners:** the existing partner screens (approve, reject, suspend, commissions, payouts, ledger, audit, exports) plus a directory to create, edit and (only when it has no history) delete partners. Referral, commission and payout history is never deleted.
* **Subscriptions / Transactions:** read-only views of ShopScale's own Paystack billing, plus a goodwill **extend period** action (no charge, recorded as an administrative extension, not a payment). Nothing here can mark a payment successful; only Paystack verification does.
* **Support:** merchants raise requests under *More → Help → Ask the ShopScale team*; you assign, set status and priority, add internal notes, reply, resolve.
* **Announcements:** drafts become visible to merchants as a banner only when published and inside their window. No email or SMS is sent.
* **Storage:** see `CLOUDFLARE_R2_SETUP.md` and `MIGRATION_AND_ROLLBACK.md`.

## Not built
Impersonation / "view as merchant" sessions are **not** implemented. The business profile page shows what support normally needs (team, subscription, payments, activity). Adding impersonation safely needs its own design review. Only one Super Admin can be created (by bootstrap); there is no screen to add more. Maintenance mode is not included.
