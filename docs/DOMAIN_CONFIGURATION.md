# ShopScale domain configuration

Status: implemented in code; **pending production verification** (no DNS, Cloudflare or EC2 was available while building). Node tests of the routing logic ran (see `docs/IMPLEMENTATION_PROGRESS.md`).

## Architecture
| Host | Serves | Hosted on |
|---|---|---|
| `shopscale.com.ng`, `www.shopscale.com.ng` | Public marketing site and merchant storefronts (`/s/:slug`, `/s/:slug/order/:token`) | Cloudflare Pages |
| `app.shopscale.com.ng` | The business application (login, register, onboarding, dashboard, POS, every module) | Cloudflare Pages (same project, second custom domain) |
| `api.shopscale.com.ng` | Spring Boot API | AWS EC2 (Docker + Nginx), Supabase PostgreSQL |

One frontend build serves both web hostnames. The mode is chosen at runtime from `window.location.hostname` (`src/config/domains.js`): `public` (root, www), `app` (app host), `unified` (localhost, preview, any other host, or `VITE_SITE_MODE=unified`). Unknown hosts get `unified`, which exposes everything, so previews and single-origin Docker installs keep working.

## Routing (`src/App.jsx`)
The path sets of the two modes are disjoint, so a redirect from one host can never return to the other.
- **Public host**: `/`, `/features`, `/retail`, `/restaurant`, `/catering`, `/pricing`, `/about`, `/contact`, `/faq`, `/privacy`, `/terms`, `/refunds`, `/s/:slug`, `/s/:slug/order/:token`, anything else = not-found page. Marketing pages never redirect an authenticated visitor.
- **App host**: `/login`, `/register`, `/forgot-password`, `/reset-password`, `/verify-email`, `/accept-invite`, `/onboarding`, `/` (dashboard or login), POS and all module routes. Unauthenticated visitors go to `/login` on the app host (`/login?next=` carries the original path; `safeNext` accepts same-origin paths only). `/s/...` links opened on the app host are sent to the root domain.
- **Cross-domain links** use `SmartLink` / `crossOrigin()`: public "Log in" and "Get started" go to `VITE_APP_URL`; app links to marketing pages go to `VITE_PUBLIC_SITE_URL`. Same-host links stay client-side.
- Deep links and refresh: Cloudflare Pages serves `index.html` for unknown paths automatically when there is no top-level `404.html`. **Do not add a `/* /index.html 200` rule** (it causes a redirect-loop warning). Docker/nginx uses `try_files` in `nginx.conf`.

## DNS (Cloudflare)
Add `shopscale.com.ng`, `www.shopscale.com.ng` and `app.shopscale.com.ng` as **custom domains of the same Pages project** (Pages creates the CNAME records when the zone is on Cloudflare). `api.shopscale.com.ng`: an A record to the EC2 public IP (or an Elastic IP), proxied or DNS-only as you prefer, with TLS terminated at Nginx (or Cloudflare "Full (strict)"). Exact targets depend on your account; none are invented here. Optional: redirect `www` to the apex with a Cloudflare redirect rule.

## Cloudflare Pages settings
Build command `npm ci && npm run build`, output `dist`, Node 20+. Environment variables (build time; they are baked into the bundle):
```
VITE_API_BASE_URL=https://api.shopscale.com.ng
VITE_PUBLIC_SITE_URL=https://shopscale.com.ng
VITE_APP_URL=https://app.shopscale.com.ng
# optional: VITE_SUPPORT_EMAIL, VITE_WHATSAPP_NUMBER, VITE_GA_MEASUREMENT_ID
```
A production build **fails** if `VITE_API_BASE_URL`, `VITE_PUBLIC_SITE_URL` or `VITE_APP_URL` is missing (`scripts/check-env.mjs`, unless `VITE_SITE_MODE=unified`). No secret may be placed in a `VITE_*` variable. `public/_headers` sets `X-Robots-Tag: noindex` on the app host and previews; `robots.txt` and `sitemap.xml` list only marketing pages.

## Backend environment (EC2)
```
CORS_ALLOWED_ORIGINS=https://app.shopscale.com.ng,https://shopscale.com.ng,https://www.shopscale.com.ng
FRONTEND_URL=https://app.shopscale.com.ng      # links in invitation / reset emails
STOREFRONT_URL=https://shopscale.com.ng/s
PUBLIC_API_URL=https://api.shopscale.com.ng
COOKIE_SECURE=true
COOKIE_SAMESITE=Strict                          # app and api share the site shopscale.com.ng
COOKIE_DOMAIN=                                  # leave empty: host-only cookie on api.shopscale.com.ng
```
Storefront pages on the root domain call the API cross-origin without credentials, so the root and www origins must be in `CORS_ALLOWED_ORIGINS`. Authentication happens only on the app host, so no token is shared between subdomains. See `docs/SESSION_MANAGEMENT.md`.

## Pending production verification
Everything involving real DNS/TLS, Cloudflare Pages hosting two custom domains, the `_headers` host rule, cookies between `app.` and `api.`, CORS from the real origins, Paystack return URLs and email links. Test checklist: open each public page on the root, `/login` and `/dashboard` on the root (must show not-found / not the app), `/` on the app host signed out (to `/login`), refresh on a deep link on each host, sign in and reload, open `/s/<slug>` and an order link.
