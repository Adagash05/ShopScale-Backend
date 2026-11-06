# Deployment guide (first deployment: treat as a staging run)

Nothing here has been run in the authoring environment. Do a staging deployment first and expect to fix issues.

1. **Database**: PostgreSQL 16. Create database and user `shopscale`. Flyway creates the schema (V1 to V4) on first start. Back it up daily (`pg_dump`) and test a restore.
2. **Backend**: `mvn -B verify`, then run the jar or the supplied `Dockerfile`. Set `DB_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `FRONTEND_URL`. Put it behind HTTPS (reverse proxy). Check `/actuator/health`. Turn off Swagger in production or restrict it at the proxy.
3. **Frontend**: set `VITE_API_URL` (and the optional `VITE_` values), `npm ci && npm run build`, serve `dist/` with SPA fallback to `index.html` (`nginx.conf` supplied). Add security headers including a strict Content-Security-Policy.
4. **Domains**: app at `shop.amsati.com`; the API can be a subdomain such as `api.shop.amsati.com`. Do not touch `amsati.com`.
5. **First boot checklist**: register, finish onboarding, add a product, record a sale, receive stock, invite a second person (copy the link if SMTP is off), sign in as them and confirm they cannot see profit.
6. **Before real customers**: have the legal pages reviewed, set contact details, run the end-to-end scenarios against staging, and test on a low-end Android phone on mobile data.
