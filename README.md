# ShopScale Backend

Spring Boot 3.3 · Java 21 · PostgreSQL · Flyway · JWT. An AMSATI product, built independently of any other project.

> **Verification status (read this first):** the authoring environment could not reach Maven Central, so this
> backend has **not been compiled and its tests have not been run**. Every Java file was syntax-checked with a
> real Java parser and every project import was checked to resolve, but type errors can still exist.
> Run `mvn -B verify` first and send me the output of anything that fails. See `docs/STATUS.md`.

## Run locally
```bash
cp .env.example .env            # set DB_PASSWORD and JWT_SECRET (>= 32 chars)
docker compose up -d db         # PostgreSQL 16, or use your own server
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run             # Flyway creates the schema on first start
```
API docs: http://localhost:8080/swagger-ui.html · Health: `/actuator/health`

Tests: `mvn -B verify` (unit tests only; no database needed). Eight JUnit test classes exist; none has been run yet.

## Configuration (environment variables)
| Variable | Required | Meaning |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | password required | PostgreSQL connection |
| `JWT_SECRET` | yes | HMAC key, at least 32 characters. The app refuses to start without it |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated frontend origins (default `http://localhost:5173`) |
| `PORT` | no | Default 8080 |

Business rules live in `application.yml` under `app.*`: enabled business types (`RETAIL` only for now), trial plan
(`BUSINESS`), trial days (14), grace days (3). Plan prices, limits and features live in the `plans` and
`plan_features` tables (seeded by `V2__seed_plans.sql`), never in code.

## Layout
`auth` · `business` (tenant + onboarding) · `subscription` (plans, state, enforcement) · `catalog` (products, variants,
categories) · `inventory` (stock movements) · `customer` · `sales` (atomic POS) · `expense` · `dashboard` · `audit` ·
`security` · `common`. Controllers hold no business logic.

Read `docs/SHOPScale_IMPLEMENTATION_STATUS.md` first, then `docs/SHOPScale_TEST_REPORT.md`, `docs/SHOPScale_KNOWN_LIMITATIONS.md`, `docs/SHOPScale_ARCHITECTURE_REPORT.md`, `docs/FINANCE.md`, `docs/SHOPScale_DEPLOYMENT_GUIDE.md` and `docs/SHOPScale_ENVIRONMENT_VARIABLES.md`.
