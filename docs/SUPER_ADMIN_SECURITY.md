# Super Admin security

## Model
* **Independent role.** `users.platform_role = 'SUPER_ADMIN'` is a different column from the merchant role in `business_members`. The check `@superAdmin.ok()` never reads merchant roles, so no business role can imply it.
* **No way to obtain it** except the one-time bootstrap (server environment variables, see `SUPER_ADMIN_SETUP.md`). No registration, no API to grant it, no self-assignment. The bootstrap refuses to promote an existing account.
* **Every request is re-checked on the server.** The token is only a claim: the API looks up the session row (not revoked, not expired) and the account (active, still SUPER_ADMIN) for each call. Disabling the account or revoking the session ends access immediately. The screens hide themselves, but they are not the protection.
* **Separate token.** Super Admin tokens carry `plat=SUPER_ADMIN` and a session id, expire after 30 minutes (configurable), have no business id and are not accepted by tenant endpoints as a merchant. There is no refresh cookie and no silent renewal. The console keeps the token in `sessionStorage` (this tab only, gone when the tab closes, never `localStorage`).
* **Credentials.** BCrypt (cost 12). The temporary password is never logged; sign-in is blocked until it is replaced (12+ characters). Changing the password revokes all other sessions. Logout revokes the session.
* **Brute force.** The login endpoint is limited per address (10 requests a minute) and an account locks for 15 minutes after 5 failures. Failures for unknown emails take the same time as for known ones (dummy hash comparison) and give the same message. All attempts are audited.
* **Suspension** of a business is enforced by a server filter on every business API call (cached 15 seconds, cleared at once on admin action) and by the storefront. Data is never deleted.

## Audit
`admin_audit_log` is append-only: database triggers refuse UPDATE and DELETE. Each row has the actor, action, target, time, IP, result, reason and small non-secret metadata. Reasons are mandatory for suspending, reactivating, editing, extending and resetting. An administrative action writes its audit row in the same transaction: if the audit row cannot be written, the action is rolled back. Partner actions made through the existing partner API with a Super Admin token are audited by an interceptor (method, path, status).

## Things the console never does
Show or store card data, Paystack keys, R2 secrets, passwords or tokens; mark a payment successful (only Paystack verification does); delete partner history, payments, sales or audit rows; offer a "delete all storage" button; send email or SMS from announcements.

## Known limits (be aware)
* The rate limiter is in memory per instance (use a shared store with several instances).
* One Super Admin, no MFA. Protect the mailbox and password; consider a network allow-list for `/api/super-admin/**` at your proxy/WAF and for `/super-admin/*` in Cloudflare Access.
* `PLATFORM_ADMIN_EMAILS` (legacy) still allows listed merchant accounts into the partner screens. Leave it empty if you do not need it.
* Impersonation is not implemented.
* Not tested against a running server in this build environment (see `IMPLEMENTATION_REPORT.md`).
