# cvg-backend

API for the **CVG FC Club Management System**: members, payments, training, matches, ID cards, FUT cards and player profiling.
Built by Devstrike Digital Limited.

- **Stack:** Kotlin · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Java 21
- **Clients:** `cvg-admin` (the Desk), `cvg-players` (the Club), `cvg-public` (the Board), all Next.js
- **Plan:** [`docs/CONTENT_PLAN.md`](docs/CONTENT_PLAN.md) · [`docs/PROFILING_QUESTIONNAIRE.md`](docs/PROFILING_QUESTIONNAIRE.md)

## Run locally

```bash
# Postgres with a cvg/cvg user and two databases
createuser -P cvg          # password: cvg
createdb -O cvg cvg
createdb -O cvg cvg_test

# First admin is created on startup when the club has no members
CVG_BOOTSTRAP_ADMIN_PHONE="0803 555 0192" \
CVG_BOOTSTRAP_ADMIN_NAME="Your Name" \
CVG_COOKIE_SECURE=false \
./gradlew bootRun
```

In development, SMS codes are printed to the log (`[SMS to +234…] 123456 is your CVG FC code…`).

```bash
./gradlew test             # integration tests against cvg_test
```

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/cvg`, `cvg`, `cvg` | |
| `CVG_BOOTSTRAP_ADMIN_PHONE` / `CVG_BOOTSTRAP_ADMIN_NAME` | — | Only used while the club has no members |
| `CVG_SMS_PROVIDER` | `log` | `log` (dev) or `termii` |
| `TERMII_API_KEY` / `TERMII_SENDER_ID` | — / `CVG FC` | Required for `termii` |
| `CVG_COOKIE_DOMAIN` | — | e.g. `.cvgfc.ng`, so the Desk and the Club share the session |
| `CVG_COOKIE_SECURE` | `true` | Set `false` for plain-HTTP local dev |
| `CVG_CORS_ORIGINS` | `http://localhost:3000,3001,3002` | Comma-separated front-end origins |

## API (M1 Foundation)

All errors look like `{"code": "jersey_taken", "message": "Jersey #7 is taken.", "fields": {...}}`.
`code` is stable for the apps; `message` is short and ready to show.

### Sign in (phone + SMS code, no passwords)

| Method | Path | Who | Body / notes |
|---|---|---|---|
| POST | `/api/auth/code` | anyone | `{"phone":"0803 555 0192"}` → `{"expiresInSeconds":600}`. 404 `not_registered`, 429 `too_many_codes` (5/hour) |
| POST | `/api/auth/verify` | anyone | `{"phone":"…","code":"123456"}` → sets the `cvg_session` cookie and returns `{token, member}`. 5 tries per code |
| GET | `/api/auth/me` | signed in | The current member |
| POST | `/api/auth/sign-out` | signed in | Revokes this session |

Web apps use the HttpOnly `cvg_session` cookie (SameSite=Lax) and **must send `X-CVG-Client: <app name>` on every POST/PUT/PATCH/DELETE**, which is the CSRF guard. Other clients can send `Authorization: Bearer <token>` instead.

### Members

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/members?status=&role=&q=` | signed in | `q` matches name, nickname, jersey or code. Phones only shown to management, or to the member themself |
| GET | `/api/members/{id}` | signed in | |
| POST | `/api/members` | ADMIN | `fullName`, `phone`, optional `nickname`, `jerseyNumber`, `status` (default `TRIALIST`), `joinedOn` (default today), `roles` |
| PUT | `/api/members/{id}` | ADMIN | Full replace of `fullName`, `nickname`, `phone`, `jerseyNumber`, `joinedOn` |
| PUT | `/api/members/{id}/status` | ADMIN | `TRIALIST` · `ACTIVE` · `INACTIVE` · `LEFT` (LEFT = no sign-in, history kept) |
| PUT | `/api/members/{id}/roles` | ADMIN | Any of `ADMIN`, `COACH`, `TREASURER`, `CAPTAIN`. The last admin can't be removed |

Rules: member codes (`CVG-0001`…) are never reused; phones are unique; jersey numbers are unique among trialist and active members.

### Seasons

| Method | Path | Who |
|---|---|---|
| GET | `/api/seasons` · `/api/seasons/current` | signed in |
| POST | `/api/seasons` (`name`, `startsOn`, `endsOn`, `activate`) | ADMIN |
| POST | `/api/seasons/{id}/activate` | ADMIN (only one active season) |

### Audit

`GET /api/audit?page=&size=&entityType=` (ADMIN, COACH, TREASURER) returns every change, with who made it and the before → after values. It's append-only.

## Layout

```
src/main/kotlin/ng/cvgfc/api/
  auth/      SMS codes, sessions, security filter
  member/    members, roles, first-admin bootstrap
  season/    seasons
  audit/     append-only change log
  common/    errors, phone numbers, hashing
  config/    properties, security, clock
src/main/resources/db/migration/   Flyway SQL
```
