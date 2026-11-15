# Environment variables

Complete list for the features added with R2 and the Super Admin console. For the older variables (database, JWT, Paystack, mail …) see `SHOPScale_ENVIRONMENT_VARIABLES.md`. None of these values may ever be committed or sent to the browser.

## Cloudflare R2
| Variable | Required | Meaning |
|---|---|---|
| `CLOUDFLARE_R2_ACCOUNT_ID` | for R2 | Cloudflare account id. Builds the default endpoint. |
| `CLOUDFLARE_R2_ACCESS_KEY_ID` | for R2 | R2 API token key id (Object Read & Write, one bucket). |
| `CLOUDFLARE_R2_SECRET_ACCESS_KEY` | for R2 | Its secret. Never logged or returned by any API. |
| `CLOUDFLARE_R2_BUCKET_NAME` | for R2 | Bucket name (lower case letters, digits, dots, hyphens). |
| `CLOUDFLARE_R2_PUBLIC_BASE_URL` | for R2 | Public https address of the bucket, e.g. `https://img.shopscale.com.ng`. |
| `CLOUDFLARE_R2_ENDPOINT` | no | Overrides `https://<account id>.r2.cloudflarestorage.com`. |
| `MEDIA_STORAGE_MODE` | no | `AUTO` (default: R2 when fully configured) or `DB` (always PostgreSQL; rollback switch). |
| `MEDIA_SERVE_MODE` | no | `REDIRECT` (default) or `PROXY`. |

R2 is used only when the first five are set and valid. If any is missing the Storage page names it; the app keeps working on PostgreSQL. A wrong value (bucket name format, non-https address, bad mode) is reported the same way.

## Super Admin bootstrap (one time)
| Variable | Required | Meaning |
|---|---|---|
| `SHOPSCALE_SUPER_ADMIN_EMAIL` | once | Email of the platform owner. Must not belong to an existing account. |
| `SHOPSCALE_SUPER_ADMIN_INITIAL_PASSWORD` | once | Temporary password: 12–72 characters with upper, lower, digit and symbol. Must be changed at first sign-in. |
| `SHOPSCALE_SUPER_ADMIN_NAME` | no | Display name, default `Platform Owner`. |
| `SHOPSCALE_SUPER_ADMIN_SESSION_MINUTES` | no | Lifetime of a console session, default 30. |

Remove the first two after the account exists. They do nothing once a Super Admin exists, and leaving a password in the environment is a risk.

## Legacy
`PLATFORM_ADMIN_EMAILS` still lets listed merchant accounts reach `/admin/partners` (partner programme only). It is not Super Admin and grants nothing else. Leave it empty if you do not need it.
