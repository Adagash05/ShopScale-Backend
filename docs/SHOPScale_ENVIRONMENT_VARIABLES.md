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
| `VITE_SITE_URL` | Public site URL for canonical and Open Graph tags, default `https://shop.amsati.com` |
| `VITE_SUPPORT_EMAIL`, `VITE_WHATSAPP_NUMBER` | Shown on the Contact page when set |

Not yet used (arrive with Paystack): platform and merchant Paystack keys, webhook secret, credential-encryption key.
