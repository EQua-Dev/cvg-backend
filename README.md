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

Sign in with the admin phone and the **last 4 digits of that phone** as the passcode.

```bash
./gradlew test             # integration tests against cvg_test
```

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/cvg`, `cvg`, `cvg` | |
| `CVG_BOOTSTRAP_ADMIN_PHONE` / `CVG_BOOTSTRAP_ADMIN_NAME` | — | Only used while the club has no members |
| `CVG_COOKIE_DOMAIN` | — | e.g. `.cvgfc.ng`, so the Desk and the Club share the session |
| `CVG_COOKIE_SECURE` | `true` | Set `false` for plain-HTTP local dev |
| `CVG_CLUB_APP_URL` | `http://localhost:3002` | Used in onboarding links sent on WhatsApp |
| `CVG_PUBLIC_SITE_URL` | `http://localhost:3000` | Used in ID card QR codes |
| `CVG_VERIFY_SECRET` | dev value | **Set in production.** Signs ID card QR links so member codes can't be enumerated |
| `CVG_CORS_ORIGINS` | `http://localhost:3000,3001,3002` | Comma-separated front-end origins |

## API

All errors look like `{"code": "jersey_taken", "message": "Jersey #7 is taken.", "fields": {...}}`.
`code` is stable for the apps; `message` is short and ready to show.

### Sign in (phone + passcode)

A member's passcode starts as the **last 4 digits of their phone**. They can set their own 4–6 digit passcode (easy ones like `1234`/`0000` are refused). Five wrong tries lock sign-in for 15 minutes. An admin reset puts it back to the last 4 digits, unlocks it, and signs out all their devices.

| Method | Path | Who | Body / notes |
|---|---|---|---|
| POST | `/api/auth/sign-in` | anyone | `{"phone":"0803 555 0192","passcode":"0192"}` → sets the `cvg_session` cookie and returns `{token, member, usesDefaultPasscode}`. 404 `not_registered`, 400 `wrong_passcode`, 429 `passcode_locked` |
| GET | `/api/auth/me` | signed in | `{member, usesDefaultPasscode}`. When `true`, the app nudges them to set their own |
| PUT | `/api/auth/passcode` | signed in | `{"currentPasscode":"0192","newPasscode":"2580"}`. Signs out their other devices. 400 `invalid_passcode` / `weak_passcode` |
| POST | `/api/auth/sign-out` | signed in | Revokes this session |
| POST | `/api/members/{id}/reset-passcode` | ADMIN | Forgotten passcode → back to last 4 digits |

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

### Onboarding link (M2)

| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/members/{id}/onboarding-link` | ADMIN | → `{url, expiresAt}`. 14 days. A new link cancels the old one |
| GET | `/api/onboarding/{token}` | anyone | `{firstName, code, jerseyNumber, phoneHint, alreadySetUp}`. 404 `link_invalid` |
| POST | `/api/onboarding/{token}/claim` | anyone | `{"passcode":"2580"}` sets their passcode and signs them in (cookie + token). The link is then used up |

### Profile and photo (M2)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/profile/options` | signed in | Pick lists: 15 positions (with group), feet, 23 traits, 37 states |
| GET / PUT | `/api/me/profile` | signed in | Full replace of the draft: positions, foot, weak foot, strengths/weaknesses (max 3 each), height, birth date, state, preferred jersey, emergency contact, consent. Returns `complete` and `missing` (wizard order) |
| PUT | `/api/me/photo` | signed in | Multipart `file`, JPEG/PNG/WebP up to 2MB (type checked from the bytes) |
| GET | `/api/members/{id}/profile` | signed in | Squad-mates get football details only; member + management also get birth date, emergency contact, consent |
| GET | `/api/members/{id}/photo` | signed in | Cached by ETag |

### Profiling questionnaire (M2)

The questions and their scoring live in `src/main/resources/profiling/questionnaire-v1.json` (from `docs/PROFILING_QUESTIONNAIRE.md`). Points are never sent to the apps.

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/me/profiling/questionnaire` | signed in | 6 common + 8 for their position group (+1 unscored). 409 `no_position` until a main position is set |
| POST | `/api/me/profiling` | signed in | `{"version":1,"answers":{"A1":"b",...}}` → `{label, topPlan, planFits, mainRole, secondaryRole, lowConfidence, coachPick}` |
| GET | `/api/me/profiling` | signed in | Latest result (204 if none) |
| GET | `/api/members/{id}/profiling` | ADMIN, COACH, CAPTAIN | |

### ID card (M2)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/me/card` | signed in | Everything printed on the card, including the signed `verifyUrl` for the QR code |
| GET | `/api/members/{id}/card` | management | |
| GET | `/api/public/verify/{code}/{signature}` | anyone | Name, member ID, status, current yes/no, season. Photo and position only with the member's consent |
| GET | `/api/public/verify/{code}/{signature}/photo` | anyone | Only with consent |

## Layout

```
src/main/kotlin/ng/cvgfc/api/
  auth/      passcodes, sessions, security filter
  onboarding/  join links
  profile/   player profile, photos, pick lists
  profiling/ questionnaire scoring
  card/      ID card and public verification
  member/    members, roles, first-admin bootstrap
  season/    seasons
  audit/     append-only change log
  common/    errors, phone numbers, hashing
  config/    properties, security, clock
src/main/resources/db/migration/   Flyway SQL
```
