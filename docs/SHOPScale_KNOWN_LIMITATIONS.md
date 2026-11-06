# Known limitations

## Verification
1. The backend has never been compiled or run. Type errors, query errors or start-up errors may exist.
2. No integration, tenant-isolation, concurrency or end-to-end tests exist yet.

## Missing product areas
Storefront and online orders, Paystack (subscriptions and merchants), subscription payments, renewals, upgrades, downgrades, cancellation and promo codes, restaurant/kitchen/ingredient tools, catering orders, delivery, custom domains, super admin, notifications, password reset and email verification, CSV import/export, image upload, purchase cancellation, partial refunds, stock transfers, locations, ownership transfer, offline POS.

## Behaviour to know about
* Plans can only be changed in the database until billing exists. A trial that ends makes the account read-only (data is never deleted).
* Permission changes and deactivation apply immediately on one server instance and within 15 seconds across several (permission cache).
* The sign-in rate limiter is per server instance.
* Access tokens last 15 minutes; the business and role come from the database on every request, not from the token.
* Default roles are fixed in code. Custom roles cover customisation.
* Estimated profit is only as good as the cost prices entered. Receiving stock updates cost with a weighted average; products with no cost are flagged.
* Purchases are not expenses (their cost appears in profit when sold), so purchase payments never reduce profit twice.
* Insight thresholds are fixed constants (`InsightRules`), not yet configurable per business.
* Refunds are whole-sale only; money handed back to a customer is not recorded.
* Invitation emails need SMTP settings; without them the link is shown to the inviter. Invitation tokens are shown once.
* Legal pages are drafts and must be reviewed by a lawyer before launch. The refund policy states no refund window because none has been decided.
* The public website uses illustrations with sample figures, not real screenshots. Take real screenshots from a running instance.
* Contact details appear on the public site only when `VITE_SUPPORT_EMAIL` / `VITE_WHATSAPP_NUMBER` are set.
* Auth tokens are stored in browser `localStorage`; deploy with a strict Content-Security-Policy.

## External dependencies still needed
PostgreSQL 16, Maven and Java 21 to build, an SMTP provider (optional), Paystack platform and merchant keys (for the billing and storefront work), DNS and SSL provider (custom domains).
