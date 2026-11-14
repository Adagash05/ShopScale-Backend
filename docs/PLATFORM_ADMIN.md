# Platform administrator guide

A **platform administrator** runs ShopScale itself (today: the Partners programme). It is separate from a **merchant** Owner/Manager, who runs one business. Business roles give **no** access to platform administration, and partners (affiliates) cannot reach it either.

## How access works
* There is **no public admin registration** and no default account or password.
* An account is a platform administrator only if its email is in the server setting **`PLATFORM_ADMIN_EMAILS`** *and* the account is active with a verified email. The list lives in server configuration; no screen or API request can change it, so nobody can grant themselves access.
* Every admin API (`/api/admin/partners/**`) is checked on the server (`@PreAuthorize("@partners.admin()")`); the screens only hide themselves.

## Create the first administrator in production
**Prerequisites:** the backend is deployed with its database; `APP_ENCRYPTION_KEY`, `FRONTEND_URL` and mail settings are already configured (they are needed by the app anyway).

1. Pick the administrator's email and a strong password (12–72 characters with lower case, upper case, a digit and a symbol). Generate one with a password manager, or: `openssl rand -base64 24`.
2. In your host's **secret / environment settings** (not in git, not in a chat) set:
   ```
   PLATFORM_ADMIN_EMAILS=you@yourdomain.com
   PLATFORM_ADMIN_BOOTSTRAP_EMAIL=you@yourdomain.com
   PLATFORM_ADMIN_BOOTSTRAP_PASSWORD=<the strong password>
   ```
   (`PLATFORM_ADMIN_BOOTSTRAP_NAME` optionally sets the display name.)
3. Restart / redeploy the backend. On start-up it creates the account (password hashed with the normal password encoder, email marked verified) and logs one line: *"Platform administrator account created…"*. The password is never logged.
4. Open `https://app.<your-domain>/login?next=/admin/partners`, sign in, and you land in **Partner administration**.
5. **Immediately delete `PLATFORM_ADMIN_BOOTSTRAP_EMAIL` and `PLATFORM_ADMIN_BOOTSTRAP_PASSWORD`** from the environment and redeploy. Change the password from the account screen if it was ever pasted anywhere.

### Safety properties of the bootstrap (covered by `PlatformAdminBootstrapTest`)
* Does nothing unless both variables are set.
* Refuses to run unless the bootstrap email is already in `PLATFORM_ADMIN_EMAILS`.
* Refuses weak passwords.
* Runs **only if no listed administrator has an account yet**, so it can never create a second "initial" administrator, and it never changes the password of, or promotes, an existing account.
* Leaving the variables in place after the first start is harmless (it does nothing again) but they should still be removed.

## Add more administrators
Add their email to `PLATFORM_ADMIN_EMAILS` (comma-separated) and redeploy. They then register normally on the app, verify their email, and sign in at `/login?next=/admin/partners`. Remove an email from the list and redeploy to revoke access immediately (the check runs on every request).

## Manage partners (what the dashboard offers)
| Task | Where / API |
|---|---|
| See all partners, filter, open one | Partners / `GET /api/admin/partners/affiliates`, `…/{id}` |
| Approve or reject applications | Applications / `POST …/{id}/approve`, `/reject` |
| Activate or deactivate a partner | Partner page / `POST …/{id}/suspend`, `/reactivate` |
| Referral code, referred merchants, referral status | Partner page, Referrals |
| Commissions: pending, available (approved), paid, reversed | Ledger / `GET …/commissions` |
| Adjust or reverse a commission | Ledger (audited) |
| Withdrawals: approve, reject, processing, paid, failed | Withdrawals |
| Performance and activity | Overview, `GET …/performance`, Audit and exports |

Partners are created by **applying and being approved**, which is the existing business model; there is no "create a partner on their behalf" action. Commission rates and terms are unchanged and live in the programme settings.

## Not covered
Platform administration beyond the Partners programme (for example suspending a merchant business) does not exist in ShopScale yet.
