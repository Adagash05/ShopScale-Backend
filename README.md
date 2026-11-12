# ShopScale Backend

Spring Boot 3.3 · Java 21 · PostgreSQL · Flyway · JWT. An AMSATI product, built independently of any other project.

> **Start with `docs/SHOPScale_V1_OWNER_GUIDE.md`** - the owner's guide to what exists, how to configure, deploy and operate it.
>
> **Verification status:** Migrations V1-V15 were applied to a clean PostgreSQL 16. The Maven build and tests have **not** been run
> (no network where this was authored). Run `mvn -B verify` first. See `docs/IMPLEMENTATION_PROGRESS.md`.

## Run locally
```bash
cp .env.example .env            # set DB_PASSWORD and JWT_SECRET (>= 32 chars)
docker compose up -d db         # PostgreSQL 16, or use your own server
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run             # Flyway creates the schema on first start
```
API docs: http://localhost:8080/swagger-ui.html · Health: `/actuator/health`

Tests: `mvn -B verify` (unit tests only; no database needed). JUnit test classes exist; they have not been run yet.

## Configuration (environment variables)
| Variable | Required | Meaning |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | password required | PostgreSQL connection |
| `JWT_SECRET` | yes | HMAC key, at least 32 characters. The app refuses to start without it |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated frontend origins (default `http://localhost:5173`) |
| `PORT` | no | Default 8080 |

Business rules live in `application.yml` under `app.*`: enabled business types, trial plan
(`BUSINESS`), trial days (14), grace days (3). Plan prices, limits and features live in the `plans` and
`plan_features` tables (seeded by `V2__seed_plans.sql`, current values set by `V15__final_pricing_limits_features.sql`), never in code.

## Layout
`auth` · `business` · `subscription` (plans, state, billing) · `store` (online store) · `payment` (Paystack client) · `catalog` · `inventory` · `branch` · `transfer` · `customer` · `pricing` · `sales` · `purchasing` · `expense` · `finance` · `dashboard` · `insight` · `notification` · `audit` · `exportdata` · `team` · `security` · `common`. Deferred (no UI): `recipe`, `restaurant`, `wastage`.

## Documents
| File | What |
|---|---|
| `docs/SHOPScale_V1_OWNER_GUIDE.md` | **Primary reference** |
| `docs/IMPLEMENTATION_PROGRESS.md` | Status, verification, deferred work |
| `docs/STOREFRONT_AND_BILLING.md` | Paystack flows and variables |
| `docs/SHOPScale_ENVIRONMENT_VARIABLES.md`, `docs/SHOPScale_DEPLOYMENT_GUIDE.md` | Configuration and deployment |
| Frontend `docs/GA4_SETUP.md` | Google Analytics setup |
(Older `STATUS.md`, `SHOPScale_IMPLEMENTATION_STATUS.md`, `SHOPScale_TEST_REPORT.md` are superseded stubs.)
