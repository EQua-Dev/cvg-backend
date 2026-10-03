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

### Money (M3)

Amounts are always **kobo** (`200000` = ₦2,000). The ledger is **append-only**: Postgres triggers refuse any `UPDATE` or `DELETE` on `ledger_entry` and `ledger_receipt`. A mistake is fixed by a reversal entry that points at the original. Every money action goes in the audit log.

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/money/categories` | ADMIN, TREASURER | Category codes and labels, with which can be collected and which are expenses |
| GET | `/api/collections?open=true` | ADMIN, TREASURER | Each with member count, paid/partial counts, expected and collected |
| POST | `/api/collections` | ADMIN, TREASURER | `title, type, amountKobo, dueDate, audience (ACTIVE / ACTIVE_AND_TRIALISTS / SELECTED + memberIds), recurring`. `recurring` is for monthly dues only: titled "October 2026 dues" and created again automatically on the 1st of each month (00:10 Abuja time) for the same audience |
| GET | `/api/collections/{id}` | ADMIN, TREASURER | Everyone on it: paid, owed, `PAID / PARTIAL / UNPAID` (unpaid first) |
| PUT | `/api/collections/{id}/members` | ADMIN, TREASURER | Replace who owes. People who already paid can't be removed |
| POST | `/api/collections/{id}/close` | ADMIN, TREASURER | No more payments against it |
| POST | `/api/ledger/payments` | ADMIN, TREASURER | Money in: `memberId, collectionId?, category? (if no collection), amountKobo, method (CASH/TRANSFER/POS), occurredOn?, note?`. Paying a collection you weren't on adds you to it |
| POST | `/api/ledger/expenses` | ADMIN, TREASURER | Money out: `category, amountKobo, method, occurredOn?, note` (note required) |
| POST | `/api/ledger/{id}/reverse` | ADMIN, TREASURER | `{"reason":"…"}`. Once per entry; corrections can't be reversed |
| PUT / GET | `/api/ledger/{id}/receipt` | ADMIN, TREASURER (GET also the member it's about) | Photo of a receipt. Expenses without one are `flagged` |
| GET | `/api/ledger?month=2026-10` | ADMIN, TREASURER | Entries, money in/out for the month, all-time balance, flagged count |
| GET | `/api/me/dues` | signed in | Open collections I'm on (owed first) with what I've paid, total owed, and my payment history |

### Training and attendance (M4)

Times are Abuja local. **Everyone is "in" by default.** Players can change their own availability until **2 hours** before the start; coach, captain and admin can change it any time before close. Attendance % = (present + late) ÷ (compulsory sessions − excused); optional sessions only add "extras".

| Method | Path | Who | Notes |
|---|---|---|---|
| GET / POST | `/api/training/patterns` | GET all, POST ADMIN, COACH | Weekly slots: `weekday (1=Mon…7), startTime "17:30", venue, kind COMPULSORY/OPTIONAL`. Sessions for the next 14 days are created from them (on change and nightly) |
| DELETE | `/api/training/patterns/{id}` | ADMIN, COACH | Stops the slot; its future unmarked sessions go too |
| GET | `/api/training/sessions?from=&to=` | signed in | Default: today to 14 days ahead. Each: date, time, venue, kind, focus, status, in/out counts, and `me` (my status, reason, locked, lockAt) |
| POST | `/api/training/sessions` | ADMIN, COACH | Impromptu: `date, startTime, venue, kind, focus` |
| GET | `/api/training/sessions/{id}` | signed in | Plus the full `roster` (availability, reason, mark) for coach, captain, admin |
| PUT | `/api/training/sessions/{id}/focus` · POST `/cancel` | ADMIN, COACH | |
| PUT | `/api/training/sessions/{id}/availability` | signed in | `{"status":"OUT","reason":"WORK"}` (reasons: INJURED, SICK, TRAVELLING, WORK, FAMILY, OTHER). 409 `availability_locked` after the cutoff |
| PUT | `/api/training/sessions/{id}/availability/{memberId}` | ADMIN, COACH, CAPTAIN | Any time before close; audited |
| PUT | `/api/training/sessions/{id}/marks` | ADMIN, COACH, CAPTAIN | `{"marks":[{"memberId","mark":"PRESENT|LATE|ABSENT|EXCUSED|null","markedAt"}]}`. From the session day. Built for offline sync: the newest tap (by `markedAt`) wins. After close only coach/admin, audited |
| POST | `/api/training/sessions/{id}/close` | ADMIN, COACH, CAPTAIN | Unmarked members become ABSENT |
| GET | `/api/training/stats` | ADMIN, COACH, CAPTAIN | Per member (active season): percent, streak, present/late/excused/absent, extras, no-shows. Best first |
| GET | `/api/training/me` | signed in | My stats and last 12 sessions |

### Matches (M5)

Availability works like training: everyone is in unless they say out (with a reason), locked 2h before kickoff.

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/matches/options` | signed in | Formation templates (slots with x/y pitch positions), game plans, past opponents and venues, opinion tags |
| GET | `/api/matches?when=upcoming|past` | signed in | Fixtures with in/out counts, my availability, my lineup spot once published, vote/opinion flags |
| POST | `/api/matches` | ADMIN, COACH | `{"opponent","date","kickoff","meetTime?","venue","side":"HOME|AWAY|NEUTRAL","type":"FRIENDLY|TOURNAMENT|LEAGUE|INTERNAL","teamSize":5|7|9|11,"gamePlan?","planB?","kit?","feeKobo?","notes?"}` |
| GET / PUT | `/api/matches/{id}` | signed in / ADMIN, COACH | Detail: match, lineup (staff always; players once published), result, POTM, opinions |
| POST | `/api/matches/{id}/cancel` | ADMIN, COACH | |
| PUT | `/api/matches/{id}/availability` | signed in | `{"status":"IN|OUT","reason"}` for myself, before the cutoff |
| GET | `/api/matches/{id}/squad` | ADMIN, COACH, CAPTAIN | Everyone with position and in/out |
| PUT | `/api/matches/{id}/availability/{memberId}` | ADMIN, COACH, CAPTAIN | Audited |
| GET | `/api/matches/{id}/selection?formation=4-3-3` | ADMIN, COACH, CAPTAIN | Every player scored 0–100 for every spot (position fit, game plan fit, attendance over the last 8 compulsory sessions, form over the last 5 matches, dues). Factors with no data yet (OVR until M6, plan fit when no plan) are dropped and the rest scaled to 100. Includes an auto-filled XI and a bench (ranked by Plan B when set) |
| PUT | `/api/matches/{id}/weights` | ADMIN, COACH | `{"weights":{"POSITION":30,...}}` for this match |
| PUT | `/api/matches/{id}/lineup` | ADMIN, COACH | `{"formation","slots":[{"idx","memberId"|"guestName"}],"captainId","penaltyTakerId","freeKickTakerId","cornerTakerId"}`. Pitch slots are 0..n-1, bench 100+ |
| POST | `/api/matches/{id}/lineup/publish` | ADMIN, COACH | Every pitch spot must be filled. Re-saving after publishing updates what players see |
| PUT | `/api/matches/{id}/result` | ADMIN, COACH | `{"ourScore","theirScore","appearances":[{"memberId"|"guestName","started","position"}],"goals":[{"scorerId"|"scorerGuest"|"ownGoal":true,"assistId?","minute?","kind?"}],"cards":[...]}`. From match day. One goal row per CVG goal. First save opens a 48h POTM vote and, if there is a fee, a match-fee collection for everyone who played. Corrections are audited |
| POST | `/api/matches/{id}/potm/vote` | matchday squad | `{"nomineeId"}`: someone who played, not yourself. Can change until it closes. Anonymous; totals only after closing; ties are joint |
| POST | `/api/matches/{id}/potm/close` | ADMIN, COACH | Close early |
| PUT | `/api/matches/{id}/opinion` | matchday squad | `{"commendTags":[≤3],"commendText?","critiqueTags":[≤3],"critiqueText?","selfRating?":1-10}`. Anonymous to players; admin and coach see names |
| GET | `/api/matches/me/record`, `/api/matches/record/{memberId}` | signed in / staff | Played, started, goals, assists, POTM, clean sheets, recent matches |
| GET | `/api/public/matches` | public | Next fixture and last 10 results (scorers and POTM by nickname or first name) |

### Ratings and FUT cards (M6)

Every active member rates every active member (themselves included) on all 20 attributes with a 5-step scale (2/4/6/8/10) or "don't know" (0). Answers save as you go and are never shown with the rater. When a window closes, each attribute becomes a card stat: peer scores (top/bottom 10% dropped from 8 scores up) averaged with the player's own at half weight, ×10, clamped 30–99. The card shows the 6 stats of the player's position group; OVR = their average; published only if each has 5+ peer ratings. Tiers: Bronze < 65, Silver 65–74, Gold 75–84, CVG Elite 85+.

| Method | Path | Who | Notes |
|---|---|---|---|
| GET / POST | `/api/ratings/windows` | ADMIN, COACH | List with completion (`finished` of `raters`) / open `{"title?","days":7}`; one at a time; titled "Season · Round N" |
| POST | `/api/ratings/windows/{id}/close` | ADMIN, COACH | Works out the cards. Windows also close themselves at their end date (checked every 15 min) |
| GET / PUT | `/api/ratings/stat-sets[/{group}]` | signed in / ADMIN, COACH | The 6 card stats per group and the 20 attributes. Changing a set needs no re-vote |
| GET | `/api/ratings/me` | signed in | Open window and everyone to rate with progress; 204 when none is open |
| GET / PUT | `/api/ratings/me/{memberId}` | active members | One player's sheet (their group's block first; last round's answers pre-filled) / save `{"scores":{"PAC":8,"REF":0}}` |
| GET | `/api/cards/me` | signed in | Latest card with my self-rating per stat, plus history |
| GET | `/api/cards`, `/api/cards/{memberId}` | signed in | Squad cards (numbers only when published), all four group cards, best-fit group |
| GET | `/api/public/squad`, `/api/public/squad/{id}/photo` | public | Published cards of members who agreed to be shown |

Selection (M5) now uses each player's card OVR for the slot's position group.

## Layout

```
src/main/kotlin/ng/cvgfc/api/
  auth/      passcodes, sessions, security filter
  onboarding/  join links
  profile/   player profile, photos, pick lists
  profiling/ questionnaire scoring
  card/      ID card and public verification
  money/     collections, ledger, monthly dues job
  training/  schedule, sessions, availability, attendance, stats
  rating/    rating windows, peer ratings, card maths, FUT cards
  match/     fixtures, formations, assisted selection, lineup, result, POTM, opinions, records
  member/    members, roles, first-admin bootstrap
  season/    seasons
  audit/     append-only change log
  common/    errors, phone numbers, hashing
  config/    properties, security, clock
src/main/resources/db/migration/   Flyway SQL
```
