# Storefront, merchant Paystack and subscription billing

## Two separate payment flows
1. **Merchant storefront** - the merchant's own Paystack keys (Online store > Connect Paystack). Money goes to the merchant.
2. **ShopScale subscriptions** - the AMSATI Paystack account, key in `PAYSTACK_SECRET_KEY`.

## Configuration required
| Variable | Purpose |
|---|---|
| `APP_ENCRYPTION_KEY` | base64 of 32 random bytes (`openssl rand -base64 32`). Encrypts merchants' Paystack secrets. Keep it safe; losing it means merchants re-enter keys. |
| `PAYSTACK_SECRET_KEY` | AMSATI Paystack secret key (subscriptions). |
| `PUBLIC_API_URL` | Public URL of this API (webhook URL shown to merchants, image links). |
| `FRONTEND_URL` | App URL (Paystack return pages). `STOREFRONT_URL` optional (default `FRONTEND_URL/s`). |
| `CORS_ALLOWED_ORIGINS` | App origin(s), comma separated. `/api/public/**` allows any origin (no credentials). |

In the **AMSATI** Paystack dashboard set the webhook URL to `https://<api>/api/webhooks/paystack/subscriptions`.
Each **merchant** sets `https://<api>/api/webhooks/paystack/store/<their-slug>` (shown in their store settings).

## Safety rules implemented
HMAC-SHA512 signature check on raw body; payment re-verified with Paystack's API; amount (kobo), currency and reference must match; idempotent (row lock); no activation from redirects; secrets never returned by any endpoint.

## Subscription behaviour
Checkout -> Paystack -> return page calls `POST /api/subscriptions/verify` (server asks Paystack) and/or webhook arrives; first one activates. Same plan and cycle while active = renewal (time stacks). Different plan = change: unused time credited; target plan's user/location limits must fit. Cancel keeps access until period end. Expired or cancelled plans fall back to Free; data is never deleted.
