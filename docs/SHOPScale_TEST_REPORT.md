# ShopScale: test report (cycle 3)

Only checks that were actually executed are listed as passed.

## Executed and passed
| Check | Result |
|---|---|
| Frontend `npm install` | Succeeded |
| Frontend `npm run build` (production) | Succeeded |
| Frontend `npm test` (Vitest) | **49 passed, 0 failed** in 9 files: cart maths, purchase maths, API client (token refresh, shared refresh, sign-out, offline, field errors), formatters and plan helpers, permission helpers, navigation by permission, login, onboarding, accept-invitation, public website (honest "coming soon", SEO tags, no fake contact details, 404), charts, insights panel |
| Backend pure logic compiled with the JDK compiler and run in a harness: `InsightRules`, `CostMath`, `Permission`, `DefaultRoles`, `Role` | **109 checks passed, 0 failed** (weighted-average cost, stock cover, reorder quantity, margin, period change, overdue rule, default role permissions, no-escalation rule, catalogue completeness) |
| All 120 Java source and test files parsed with a Java parser | 0 syntax errors |
| Every project import in the Java code resolves to an existing class | Passed |
| All 4 Flyway migrations parsed with the PostgreSQL parser (61 statements) | 0 syntax errors |
| Every JPA entity field matched against the migration columns (what `ddl-auto: validate` checks) | 0 mismatches, 0 unmapped columns |
| 240 JPQL property references checked against entity fields | 0 problems |

You can re-run that harness with only a JDK: `sh tools/run-pure-logic-check.sh`.

## Written but NOT run (Maven Central unreachable in the authoring environment)
`mvn compile`, and these JUnit tests: `DateRangeTest`, `SlugRulesTest`, `SubscriptionStateTest`, `JwtServiceTest`, `DefaultRolesTest`, `PermissionAnnotationsTest`, `CostMathTest`, `InsightRulesTest`. The harness above covers the logic of the last two and `DefaultRoles` only.

## Not tested at all
* Backend start-up, Flyway on a real PostgreSQL, Hibernate query execution, Spring Security wiring.
* Any API call, the frontend against a live backend, and every end-to-end business scenario in the specification.
* Tenant isolation, concurrent sales and double-submit protection, permission enforcement at runtime, invitation acceptance, email delivery.
* Mobile devices and performance.
* Anything involving Paystack, custom domains, storefronts, restaurant or catering (not built).

## How to run everything
Backend: `cd ShopScale-Backend && mvn -B verify` (needs Maven 3.9 and Java 21). Frontend: `cd ShopScale-Frontend && npm install && npm test && npm run build`.
