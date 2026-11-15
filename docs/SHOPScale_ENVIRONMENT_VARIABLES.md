# Environment variables

## Backend
| Variable | Required | Meaning |
|---|---|---|
| `DB_URL` | no | JDBC URL, default `jdbc:postgresql://localhost:5432/shopscale` |
| `DB_USERNAME` | no | Default `shopscale` |
| `DB_PASSWORD` | **yes** | Database password |
| `JWT_SECRET` | **yes** | At least 32 characters (`openssl rand -base64 48`). The app will not start without it |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated frontend origins, default `http://localhost:5173` |
| `FRONTEND_URL` | no | Base of invitation links, default `http://localhost:5173` |
| `MAIL_FROM` | no | Sender address. Email is sent only if this and `SPRING_MAIL_HOST` are both set |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | no | Standard Spring Boot SMTP settings |
| `PORT` | no | Default 8080 |

Business rules in `application.yml` (`app.*`): enabled business types (`RETAIL`), trial plan (`BUSINESS`), trial days (14), grace days (3), invitation lifetime (7 days).

## Frontend (build time, `VITE_` prefix)
| Variable | Meaning |
|---|---|
| `VITE_API_URL` | Backend base URL, no `/api` |
| `VITE_SITE_URL` | Public site URL for canonical and Open Graph tags, default `https://shopscale.fidmap.co` |
| `VITE_SUPPORT_EMAIL`, `VITE_WHATSAPP_NUMBER` | Shown on the Contact page when set |

See STOREFRONT_AND_BILLING.md for `APP_ENCRYPTION_KEY`, `PAYSTACK_SECRET_KEY`, `PUBLIC_API_URL`, `STOREFRONT_URL`. Merchants' Paystack keys are entered in the app, not in env.

## ShopScale Partners
| Variable | Required | Purpose |
|---|---|---|
| `PLATFORM_ADMIN_EMAILS` | to administer the programme | Comma-separated emails allowed into `/admin/partners`. The account must exist, be active and have a verified email. Empty = nobody has admin access. |
| `PARTNERS_REFERRAL_BASE_URL` | no | Host used in referral links (`…/register?ref=CODE`). Defaults to `FRONTEND_URL`. Must be the host that serves `/register` (the app host). |
| `APP_ENCRYPTION_KEY` | yes (already required) | Encrypts partners' bank account numbers. Saving bank details is refused without it. |
| `FRONTEND_URL`, `MAIL_*` / Resend | yes (already required) | Links and notification emails. |

## Cloudflare R2 and Super Admin (added 2026-11-14)

See `ENVIRONMENT_VARIABLES.md` for the complete table, including these. Summary: `CLOUDFLARE_R2_*` (six variables) and `MEDIA_STORAGE_MODE`, `MEDIA_SERVE_MODE` control image storage; `SHOPSCALE_SUPER_ADMIN_EMAIL`, `SHOPSCALE_SUPER_ADMIN_INITIAL_PASSWORD`, `SHOPSCALE_SUPER_ADMIN_NAME`, `SHOPSCALE_SUPER_ADMIN_SESSION_MINUTES` control the one-time platform-owner bootstrap.
