# Session management

Status: implemented; backend JUnit and frontend vitest suites **not run** (no Maven/npm network). Migration V16 applied to PostgreSQL 16 successfully. **Pending production verification** for cookies between real hosts.

## Design
- **Access token**: JWT, 15 minutes, held in JavaScript memory only (never localStorage).
- **Refresh token**: random opaque value in an `HttpOnly; Secure; SameSite=Strict; Path=/api/auth` cookie named `ss_rt`, host-only on the API host. Lifetime **30 days, sliding** (each rotation extends it). Only a hash is stored (`refresh_tokens`, V16).
- **Rotation**: every refresh issues a new token and marks the old one `rotated_at`. A rotated token presented again within **120 seconds** is treated as a lost response / second tab: it is accepted once and the live token of that family is rotated. A replay outside that window **revokes the whole family** (theft response).
- **CSRF**: the cookie is SameSite=Strict and `/refresh` and `/logout` additionally require header `X-ShopScale-Client: web` and an `Origin` in `CORS_ALLOWED_ORIGINS`.
- **Multiple devices**: each login creates its own family with a device label (browser/OS, from the user agent) and last-used time. `GET /api/auth/sessions`, `DELETE /api/auth/sessions/{id}`, `POST /api/auth/logout-all`. Settings > Devices signed in (`src/pages/DeviceSessions.jsx`) lists them with per-device sign-out and "Sign out everywhere".
- **Logout** revokes the family server-side and clears the cookie. Password reset revokes all sessions.
## Frontend (`src/lib/api.js`, `src/auth/AuthContext.jsx`)
On start the app calls `/auth/refresh` (cookie) to restore the session; concurrent 401s share one refresh promise. **Only a 401/403 from the server signs the user out.** Network errors and 5xx keep the session. A snapshot (user, business, permissions; no credentials) in `src/lib/sessionCache.js` lets the app open offline.
## Why users were logged out too quickly before
A network failure during refresh was treated as sign-out, and concurrent refreshes from two tabs revoked the token family. Both are fixed above.
## Limits
Browser-restart persistence, 30-day expiry, cross-subdomain cookie delivery and the Origin allowlist are untested against real browsers/hosts.
