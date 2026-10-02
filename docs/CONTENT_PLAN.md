# CVG FC — Club Management System: Content Plan (v1)

**Status:** Draft for sign-off · **Date:** 2 Oct 2026 · **Built by:** Devstrike Digital Limited

**Stack:** Spring Boot (Kotlin) + PostgreSQL (`cvg-backend`) · Next.js (`cvg-admin`, `cvg-players`, `cvg-public`)

**Visual language:** layouts from the CCMS design, with Archivo for text and IBM Plex Mono for numbers. Colours follow the brand palette below.

---

## 0. Look & feel

### 0.1 Brand colours (calm, not shouty)

Taken from the jersey: orange body, teal and black collar. Orange is toned down slightly and used only as an accent.

| Token | Hex | Use |
|---|---|---|
| **Ink** | `#0E1A16` | Dark backgrounds (Club app), main text on light |
| **Paper** | `#F4F5EF` | Light backgrounds (Desk app), text on dark |
| **CVG Orange** | `#E8622A` | Primary button, active tab, key numbers. **About 10% of any screen** |
| **Collar Teal** | `#2A8C99` | Secondary accents, links, selected chips, charts |
| **Slate** | `#59685F` | Secondary text |
| **Mist** | `#D8DBCE` | Borders, dividers |
| **Good** | `#2F9E6B` | Paid, present, success |
| **Caution** | `#C98A12` | Late, partial, warnings |
| **Alert** | `#C8463D` | Owing, absent, errors |

**Rule of thumb:** 60% neutral (Ink/Paper), 30% supporting (Slate/Teal), 10% orange. No full-orange screens; orange backgrounds are only for share images and the FUT card. The logo stays as is for now (redesign parked).

### 0.2 Keep it simple: tap, don't read

Many members won't read long text or click through many steps. Every screen follows these rules:

1. **One job per screen, one big button.** No menus inside menus.
2. **Tap, don't type.** Chips, toggles, sliders and photos. Text boxes are always optional.
3. **Short words.** Labels of 1–4 words, questions of 12 words or fewer, plain English. Numbers and icons instead of sentences ("16/16 · 100%", not "You have attended all sixteen sessions").
4. **Smart defaults.** Availability starts as "I'm in", forms come pre-filled from last time, and the most likely answer is pre-selected where sensible.
5. **Auto-advance.** In questionnaires, ratings and votes, a tap moves to the next screen, with progress dots at the top.
6. **Save as you go.** Players can leave anytime and resume where they stopped.
7. **The home screen is a to-do list.** It shows only what needs the player now, as cards with one-tap actions: "I'm in ✓", "Vote POTM", "Rate 4 more", "Pay ₦2,000".
8. **WhatsApp links open the exact action**, e.g. the POTM vote screen, not the home page.
9. **Big thumbs.** Tap targets at least 48px, main actions at the bottom of the screen.
10. **Instant feedback.** A short vibration, a tick animation, and a two-word toast ("Saved ✓", "You're in").

**Interactive formats used:**
- **Questionnaire:** a mini pitch picture for each scenario, 2–4 big tap answers, then auto-advance.
- **Rating the squad:** one player card at a time, each attribute rated on a **5-step tap scale** (Weak · Fair · Good · Strong · Elite = 2/4/6/8/10), then swipe to the next player.
- **POTM vote:** tap a face.
- **Match opinion:** tap 👍 or 👎 on tags. The note is optional.
- **Selection board:** drag players onto the pitch.

**Later idea:** an English / Pidgin language switch.

---

## 1. The three apps

| App | Repo | Who | Login |
|---|---|---|---|
| **The Desk** | `cvg-admin` | Members holding a management role | Phone + passcode |
| **The Club** | `cvg-players` | Every member | Phone + passcode |
| **The Board** | `cvg-public` | Anyone | None |

There's one account per person. A member with a management role uses the same phone number to sign in to both the Club and the Desk.

**Sign-in:** phone number + passcode. No SMS needed.
- **First time:** the passcode is the **last 4 digits of the member's phone**. The app then asks them to set their own (4–6 numbers), with an option to skip.
- **Rules:** no easy codes like `1234` or `0000`. Five wrong tries lock the account for 15 minutes.
- **Forgotten passcode:** an admin taps **Reset passcode**. It goes back to the last 4 digits of their phone, and all their devices are signed out.

---

## 2. Members & roles

### 2.1 Member record

| Field | Type | Set by | Notes |
|---|---|---|---|
| Member ID | auto | System | `CVG-0001`, `CVG-0002`… in join order, never reused |
| Full name | text | Admin | |
| Nickname | text | Admin / player | Optional; shown on cards if set |
| Phone | phone | Admin | Login identity, unique |
| Jersey number | 1–99 | Admin | Unique among Active members |
| Status | select | Admin | `Trialist` · `Active` · `Inactive` · `Left` |
| Joined on | date | Admin | |
| Roles | multi-select | Admin | See 2.2 |
| Profile fields | — | Player via onboarding link | See §3 |

**Status effects:** only Active and Trialist members appear in selection, dues lists, attendance and voting. Inactive and Left members keep their full history but drop out of all of those.

### 2.2 Roles (a member can hold several)

| Role | Can do |
|---|---|
| **Player** (everyone) | Club app: availability, voting, ratings, match opinions, own record, cards |
| **Admin** | Everything, including assigning roles, seasons and the audit log |
| **Coach** | Training schedule & attendance, matches, selection, post-match records, rating windows |
| **Treasurer** | Payments, collections, money audit |
| **Captain** | Mark attendance, edit availability, help with selection (no money access) |

---

## 3. Onboarding link (player self-service profile)

1. Admin adds a member (name, phone, jersey, status, roles).
2. The system creates a private link, `club.cvgfc.ng/join/<token>`, and the Desk shows a **Share on WhatsApp** button with a ready-made message.
3. The player opens the link and sets their **passcode** (4–6 numbers). This turns the link into their account. Until they set one, the passcode is the **last 4 digits of their phone**.
4. The player fills in the profile below. Most of it is picked from lists.
5. Once submitted, the link expires. Admin can generate a new one at any time. Unused links expire after 14 days.

### 3.1 Profile fields

| Field | Input | Options |
|---|---|---|
| Headshot photo | Upload + square crop | Required. Used on the ID card and the FUT card |
| Action photo | Upload | Optional |
| Favoured position | Single select | GK · CB · LB · RB · LWB · RWB · CDM · CM · CAM · LM · RM · LW · RW · CF · ST |
| Other positions | Multi select (max 3) | Same list |
| Dominant foot | Single select | Right · Left · Both |
| Weak-foot ability | 1–5 stars | |
| Strengths | Pick up to 3 | List A below |
| Weaknesses | Pick up to 3 | List A below |
| Profiling questionnaire | 14 taps (~3 min) | See §9A and `PROFILING_QUESTIONNAIRE.md`. This replaces a single "playing style" pick |
| Height | cm | Optional |
| Date of birth | date | Used to calculate age; the date itself is never shown publicly |
| State of origin | Select (36 states + FCT) | Optional, flavour for the FUT card |
| Preferred jersey number | 1–99 | Suggestion only; admin decides |
| Emergency contact | Name + phone | Required |
| Consent | Checkbox | "CVG FC can show my photo, name and ratings on club pages." |

**List A (strengths/weaknesses):** Pace · Finishing · Long shots · Passing · Vision · Crossing · Dribbling · First touch · Composure · Heading · Tackling · Marking · Positioning · Stamina · Strength · Work rate · Set pieces · Weak foot · Communication · Leadership · Shot-stopping · Distribution · Aerial duels.

---

## 4. Payments

### 4.1 Collections (the "payments" admin creates)

A **collection** is something members are expected to pay, for example "October dues", "Abuja Cup entry fee" or "New jerseys".

| Field | Notes |
|---|---|
| Title | e.g. "October 2026 dues" |
| Type | `Monthly dues` · `Club Development Contribution` · `Tournament fee` · `Kit/jersey` · `Welfare` · `Other` |
| Amount per member | ₦, stored in kobo |
| Due date | |
| Who owes | All Active members · Active + Trialists · Selected members |
| Recurring | Monthly dues can repeat automatically on the 1st of each month |

**Collection view (Desk):** one row per member showing **Paid · Partial (₦x of ₦y) · Unpaid**, a progress bar (₦ collected out of ₦ expected), a filter for unpaid members, and **Share reminder on WhatsApp**, which builds the message for you.

### 4.2 Payment records

| Field | Notes |
|---|---|
| Member | Required for collection payments |
| Collection | Optional; a payment can be unlinked (e.g. a donation) |
| Amount | Partial payments are allowed and add up |
| Method | Cash · Transfer · POS |
| Date | |
| Receipt / proof | Optional photo; entries without one are flagged |
| Recorded by, recorded at | Automatic |

**Other money:** expenses (field rental, balls, transport) and donations go in the same ledger, as money in or out with a category.

**Integrity rule:** entries are never edited or deleted. A mistake is fixed with a **reversal** entry that points to the original. Every money action is written to the audit log.

**Player view (Club):** what I owe, what I've paid, and my payment history.

---

## 5. Training & attendance

### 5.1 Schedule

| Setting | Notes |
|---|---|
| Weekly training days | e.g. Mon & Wed 5:30pm, Sat 7:00am. Each day has a venue and a type |
| Type | **Compulsory** or **Optional** |
| Impromptu session | Admin adds a one-off date and marks it compulsory or optional |
| Cancel a session | It stays in history as Cancelled and doesn't count |

The system creates upcoming sessions from the weekly pattern for the active season.

### 5.2 Availability (applies to training and matches)

- **Default is "I'm in".** Every eligible member counts as in unless they say otherwise.
- Players switch to **"I'm out"** with a reason: Injured · Sick · Travelling · Work · Family · Other.
- **Admin, Coach and Captain can change any player's availability**, and the change is logged.
- Players can change their own status until a cutoff (default: 2 hours before the start). After that only admins can change it.

### 5.3 Attendance marking (Desk, pitchside)

Tap a name to mark Present, tap again for Absent, long-press for Late or Excused, as in the design. It works offline: marks queue on the phone and sync when signal returns. **Close the session** locks it, and any later edit goes to the audit log.

### 5.4 Calculated numbers

| Metric | Rule |
|---|---|
| Attendance % | (Present + Late) ÷ (compulsory sessions held since joining − Excused) |
| Optional sessions | Shown separately as "extra sessions attended". They add to your record and never count against you |
| No-show | Said "I'm in" (or left the default) but was marked Absent. Shown to admins |
| Streak | Consecutive compulsory sessions attended |

---

## 6. Matches: before the game

### 6.1 Match record

| Field | Options |
|---|---|
| Opponent | Text (saved to an opponents list for reuse) |
| Date, kick-off time, meeting time | |
| Venue | Saved venues list + "Home / Away / Neutral" |
| Match type | Friendly · Tournament · League · Internal (CVG vs CVG) |
| Format | 5-a-side · 7-a-side · 9-a-side · 11-a-side |
| Game plan | Possession · Counter-attack · High press · Defensive / low block · Direct (see §9A) |
| Plan B | Optional second game plan for chasing or protecting a result |
| Kit colour | Select |
| Match fee | Optional; can create a collection automatically |
| Notes | |

### 6.2 Assisted selection

For each available player, the Desk shows a **selection score** for each position:

| Factor | Default weight |
|---|---|
| Position fit (favoured = 1.0, other position = 0.7, unlisted = 0.3) | 25% |
| Group OVR for the slot's position group (from the full attribute profile) | 25% |
| **Game plan fit** for the match's game plan (§9A) | 20% |
| Attendance % (last 8 compulsory sessions) | 15% |
| Recent form (POTM votes + goals + assists, last 5 matches) | 10% |
| Dues in good standing | 5% |

Admins can change the weights for each match, and the score is advice only. **Auto-fill XI** suggests a lineup that the coach then adjusts.

### 6.3 Formation & lineup

- Formation templates for each format, for example:
  - 11-a-side: 4-4-2, 4-3-3, 4-2-3-1, 3-5-2, 5-3-2
  - 9-a-side: 3-3-2, 3-2-3
  - 7-a-side: 2-3-1, 3-2-1
  - 5-a-side: 2-1-1, 1-2-1
- Drag players into the slots on a pitch view, then pick the bench, captain, penalty taker and set-piece takers.
- **Publish lineup.** Players see it in the Club app, along with a WhatsApp-ready lineup image.
- **Chemistry lines** between neighbouring slots (green / amber / red) based on role pairings (§9A.4), with a team chemistry total.
- **Plan B bench:** when a Plan B is set, the bench is ranked by fit for that plan, so the coach knows who to bring on if the game changes.
- **Guest players** (non-members) can fill a slot. They're recorded by name only and are excluded from ratings and dues.

---

## 7. Matches: after the game

### 7.1 Match result (Desk)

| Field | Notes |
|---|---|
| Final score | CVG – Opponent |
| Who actually played | Starting XI + substitutes used (pre-filled from the lineup) |
| Goals | Scorer (member, guest or own goal), optional minute, optional type (open play · penalty · free kick · header) |
| Assists | Linked to each goal; optional |
| Cards | Optional, yellow/red |
| Clean sheet | Automatic for GK and defenders who played the whole match, when no goals were conceded |

### 7.2 Player of the Match vote (Club)

- Opens when the result is saved and closes after **48 hours** (admin can close it early).
- **Who votes:** members who were in the squad for that match.
- One vote each, **not for yourself**. Votes are anonymous; only totals are shown.
- Ties are shared ("Joint POTM").

### 7.3 Match opinions (Club)

Every squad member can give:

| Part | Input |
|---|---|
| **Commendation**: what went well | Pick 1–3 tags + optional short text (280 chars) |
| **Critique**: what to fix | Pick 1–3 tags + optional short text (280 chars) |
| Self-rating for this match | 1–10, optional |

**Tags:** Pressing · Shape · Communication · Finishing · Passing · Set pieces · Defending crosses · Transitions · Work rate · Discipline · Fitness · Goalkeeping · Leadership.

**Visibility:** players see opinions **anonymously**. Admin and Coach see who wrote what. The match page shows a tag summary (e.g. "Pressing: praised by 9, criticised by 2").

---

## 8. CVG ID card

| Front | Back |
|---|---|
| Club badge, "CVG FC · Abuja" | QR code → `cvgfc.ng/verify/CVG-0009` |
| Headshot | Emergency contact |
| Full name, nickname | "If found, return to CVG FC" + club contact |
| Member ID, jersey number | Issued date |
| Favoured position, roles | |
| Status + "Valid for season 2026/27" | |

- Players can download it as a PNG (for their phone) or a print-ready PDF.
- **Public verification page** (`cvg-public`): photo, name, member ID, status (Active / Inactive / Left) and season. No phone numbers or other private data.
- It regenerates automatically when the photo, status, roles or season change.

---

## 9. FUT card (peer-rated)

### 9.1 Attribute pool and card stats

**Every member is rated on every attribute** in the pool below (20 attributes). Each member therefore has a full attribute profile, and the **card displays only the 6 stats for their favoured position's group**.

**Attribute pool:** Reflexes · Handling · Positioning (GK) · Distribution · Command of area · One-on-ones · Tackling · Marking · Aerial · Pace · Strength · Passing · Vision · Ball control · Dribbling · Stamina · Finishing · Off-ball movement · Composure · Shot power.

Card stats shown per group:

| Group | Positions | The 6 card stats |
|---|---|---|
| **GK** | GK | **REF** Reflexes · **HAN** Handling · **POS** Positioning · **DIS** Distribution · **COM** Command of area · **1V1** One-on-ones |
| **DEF** | CB, LB, RB, LWB, RWB | **TAC** Tackling · **MRK** Marking · **AER** Aerial · **PAC** Pace · **STR** Strength · **PAS** Passing |
| **MID** | CDM, CM, CAM, LM, RM | **PAS** Passing · **VIS** Vision · **CTL** Ball control · **DRI** Dribbling · **STA** Stamina · **TAC** Tackling |
| **ATT** | LW, RW, CF, ST | **FIN** Finishing · **PAC** Pace · **DRI** Dribbling · **MOV** Off-ball movement · **CMP** Composure · **SHO** Shot power |

The stat sets can be edited per group in the Desk, so the coach can swap a stat later without a re-vote, because all attributes are already rated.

**What the full profile unlocks:**
- **An OVR for every position group.** Each player has a GK, DEF, MID and ATT rating, not just one.
- **"Best fit" badge.** If a player's highest group OVR isn't their favoured group, the card says so (e.g. "Plays MID · rated best at DEF").
- **Alternate cards.** A player can view or share their card for any of their "other positions".
- **Better assisted selection.** Selection uses the player's OVR for the slot's group, so a midfielder filling in at full-back is scored on DEF stats.

### 9.2 Rating window

- Admin/Coach **opens a rating window** (e.g. "Season 2026/27 · Round 1") with an end date, and **closes it** manually or on that date.
- While it's open, every Active member rates **every Active member, including themselves**, on **all 20 attributes**.
- **Input:** a 5-step tap scale per attribute (Weak · Fair · Good · Strong · Elite → 2/4/6/8/10; from the second round onward, pre-filled with the rater's last answers), with one screen per player and a progress bar ("12 of 18 rated"). Attributes are grouped into Goalkeeping, Defending, Midfield and Attacking blocks, and the block for the rated player's own group is shown first.
- **"Don't know" option** on each attribute (e.g. nobody has seen a striker in goal). It counts as no rating, not a low one.
- **Load:** about 20 taps per player, roughly 6–8 minutes for a squad of 18. Windows default to 7 days.
- Ratings save as the player goes, so they can stop and come back.
- **Anonymous:** nobody, including admins, can see who gave whom which score. Admins see only completion ("14 of 18 members have finished").

### 9.3 How the card is calculated (when the window closes)

For each player and **each of the 20 attributes** ("Don't know" answers are ignored):

1. Collect the peer scores (excluding the player's own).
2. If there are **8 or more** peer scores, drop the top 10% and bottom 10% (at least one each). This blunts friends boosting friends and grudges dragging people down.
3. Average the rest = **peer score**.
4. **Self-rating counts at half weight:** `stat = (peer_sum + 0.5 × self) ÷ (peer_count + 0.5)`.
5. Convert to the card scale: `card_stat = round(stat × 10)`, clamped to 30–99.

**Group OVR** = the average of that group's 6 attributes, rounded. It's calculated for all four groups.

**Card OVR** = the group OVR for the player's favoured position.

**Publish rule:** a card is only published if each of its 6 stats has at least **5 peer ratings**. Otherwise it shows "Not enough ratings".

**Card tier:** Bronze (< 65) · Silver (65–74) · Gold (75–84) · **CVG Elite** (85+).

**Self vs Squad:** a player's own view shows their self-rating next to the squad's verdict for each stat. Nobody else sees this.

### 9.4 Card content

OVR · position · name/nickname · headshot · club badge · state-of-origin flag/emblem · 6 stats with labels · tier styling · season/round label.

It can be downloaded as an image and shared to WhatsApp. Each new rating round creates a new version, and old versions stay in the player's history so they can watch their card progress.

---

## 9A. Player profiling (game plans, roles, chemistry)

**Goal:** know which players suit which style of football and which players work well together, then use that for selection and substitutions. The full questionnaire is in **`PROFILING_QUESTIONNAIRE.md`**.

### 9A.1 Game plans

**POS** Possession · **CTR** Counter-attack · **PRS** High press · **BLK** Defensive / low block · **DIR** Direct.

### 9A.2 Roles per position group

| Group | Roles |
|---|---|
| GK | Sweeper-keeper · Shot-stopper · Distributor · Commander |
| DEF | Ball-playing defender · Stopper · Covering defender · Attacking full-back · Defensive full-back · Inverted full-back |
| MID | Deep-lying playmaker · Ball-winner · Box-to-box · Advanced playmaker · Ball-carrier |
| ATT | Poacher · Target man · Inside forward · Pressing forward · Link forward · Winger · Runner in behind |

### 9A.3 How a player's profile is built

**Game plan fit (0–100) for each of the 5 plans:**

| Source | Weight | Notes |
|---|---|---|
| Profiling questionnaire (self) | 35% | 14 taps, about 3 minutes |
| Attribute ratings (peers, §9) | 50% | Mapped per plan, see below |
| Match data | 15% | Only once a player has 5 or more matches under that plan. Before then, its weight is shared out between the other two |

**Attribute → game plan mapping** (average of the listed attributes):

| Plan | Outfield attributes | GK attributes |
|---|---|---|
| POS | Passing, Vision, Ball control, Composure | Distribution, Composure |
| CTR | Pace, Off-ball movement, Finishing, Dribbling | Distribution, Reflexes |
| PRS | Stamina, Pace, Tackling | One-on-ones, Command of area |
| BLK | Marking, Tackling, Strength, Aerial | Reflexes, Handling, Positioning |
| DIR | Aerial, Strength, Shot power, Pace | Distribution, Command of area |

**Role:** the questionnaire's main role, checked against the **peer role vote** (one extra question per teammate in the rating window: "What role suits [Name] best?"). Where self and peers disagree, the coach sees both and can set the final role.

**Profile label:** strongest plan + main role, e.g. *"Counter-attacking Inside Forward"*. It's shown on the profile and as a badge on the FUT card.

**Profiling rounds:** the questionnaire is part of onboarding, and admin can reopen it each season. Detailed answers are visible only to the player, Coach and Admin; the label and game plan bars are visible to the squad.

### 9A.4 Chemistry rules (starter set, editable by the coach)

| Pairing (neighbouring slots) | Link |
|---|---|
| Ball-winner + Deep-lying or Advanced playmaker | 🟢 |
| Stopper + Covering defender | 🟢 |
| Ball-playing defender + Stopper or Covering defender | 🟢 |
| Target man + Winger or Inside forward | 🟢 |
| Poacher + Advanced playmaker or Link forward | 🟢 |
| Runner in behind + Deep-lying playmaker | 🟢 |
| Sweeper-keeper behind a high-line defence (PRS plan) | 🟢 |
| Two Stoppers at centre-back (both step out, space behind) | 🟠 |
| Two Advanced playmakers in central midfield (no cover) | 🟠 |
| Two Attacking full-backs without a Ball-winner in midfield | 🟠 |
| Two Target men up front | 🔴 |
| Shot-stopper with a high line in a PRS plan | 🔴 |

Every other pairing is neutral. **Team chemistry** = green links − red links, plus a bonus for each player whose game plan fit is ≥ 70 for the selected plan.

Once ~15–20 matches are recorded, **partnership stats** (goals for and against while two players are on the pitch together) start adjusting the links. Until then, they're shown as information only.

---

## 10. Seasons & audit

- **Season:** name, start and end dates. Attendance %, goals, assists, POTM counts, collections and rating rounds all belong to a season. Career totals add up across seasons.
- **Audit log** (Admin, Treasurer, Coach): every change to money, attendance after close, availability made by someone else, roles, status and rating windows. Each entry records who, when, what changed, and the before → after values.

---

## 11. Screens per app

### The Desk (`cvg-admin`)
1. **Home:** next session/match, availability count, collections due, open votes and rating windows.
2. **Members:** list (filter by status/role) · add member · member detail · roles · onboarding link + Share on WhatsApp.
3. **Payments:** collections list · collection grid (paid/partial/unpaid) · record payment · record expense/donation · ledger · reversals.
4. **Training:** weekly schedule settings · upcoming sessions · add impromptu session · attendance marking (offline) · attendance report.
5. **Matches:** list (upcoming/past) · create match · availability · assisted selection + formation board · publish lineup · enter result, scorers and assists · close the POTM vote.
6. **Ratings:** stat sets per group · open/close window · completion tracker · publish cards.
7. **Profiling:** squad profile overview (each player's game plan fit) · open a profiling round · self-vs-peer role disagreements · set final roles · chemistry rules editor.
8. **Seasons** · **Audit log** · **Settings** (club details, venues, opponents, attendance cutoff).

### The Club (`cvg-players`)
1. **Sign in** (phone + passcode) / **Join** (onboarding link).
2. **Home:** next event with **I'm in / I'm out**, outstanding dues, open actions ("Vote POTM", "Rate your squad", "Give your match opinion").
3. **Matches:** fixtures, published lineups, results, POTM vote, opinions.
4. **My record:** attendance %, streak, sessions list, goals/assists/POTM, payments.
5. **My cards:** ID card (download) and FUT card (download/share, history, self vs squad).
6. **Rate the squad** (while a window is open) · **Profiling questionnaire** (when a round is open).
6b. **My profile:** label, role, game plan fit bars, self vs peer role.
7. **Squad:** members with their published FUT cards.
8. **Profile:** edit photo and profile fields.

### The Board (`cvg-public`)
1. **Home:** next fixture, latest result, top FUT cards.
2. **Fixtures & results**, with match pages showing the score, scorers and POTM.
3. **Squad:** published FUT cards of consenting members.
4. **Verify:** `/verify/CVG-XXXX` for ID cards.

Each public page has a WhatsApp-friendly link preview image.

---

## 12. Data model (backend entities)

`Club` · `Season` · `Member` · `MemberRole` · `OnboardingLink` · `AuthSession` · `MediaAsset` · `Venue` · `Opponent` · `TrainingPattern` · `TrainingSession` · `Availability` · `AttendanceMark` · `Collection` · `CollectionTarget` · `LedgerEntry` · `Match` · `Formation` · `LineupSlot` · `GuestPlayer` · `MatchGoal` · `MatchCard` · `PotmVote` · `MatchOpinion` · `StatDefinition` · `PositionGroupStatSet` · `RatingWindow` · `Rating` · `PlayerCard` (versioned) · `ProfilingRound` · `ProfilingQuestion` · `ProfilingOption` (with point weights) · `ProfilingResponse` · `PlayerProfile` (plan fits, role, label) · `PeerRoleVote` · `ChemistryRule` · `AuditEvent`

Questions and point weights are stored as data, not code, so the coach can adjust them without a new release.

Every table carries a `club_id`, so the platform can serve other clubs later without a rewrite.

---

## 13. Integrations

| Need | Choice |
|---|---|
| Photo storage | S3-compatible storage (e.g. Cloudflare R2) |
| Card / share images | Generated on the server as PNG |
| WhatsApp sharing | `wa.me` share links (no paid API needed for v1) |

---

## 14. Build order

| Milestone | Delivers |
|---|---|
| **M1 Foundation** | Backend skeleton, auth (phone + passcode), members, roles, seasons, audit, Desk + Club shells |
| **M2 Onboarding** | Onboarding links, profile form, photo upload, ID card + verify page |
| **M3 Money** | Collections, payment records, ledger, reversals, player dues view |
| **M4 Training** | Schedule, sessions, availability, offline attendance, attendance stats |
| **M5 Matches** | Match records, assisted selection, formations, lineup publish, results, POTM, opinions |
| **M6 FUT cards** | Stat sets, rating windows, calculation, card generation, public squad page |
| **M7 Profiling** | Questionnaire, game plan fit, roles + peer role vote, labels, chemistry lines, plan-aware selection and Plan B bench |

The questionnaire itself can go live earlier, in M2 alongside onboarding, so answers are already collected when M7 lands.

---

## 15. Later upgrades (not in v1)

Kit register and check-outs · public ledger/transparency board · Paystack virtual accounts for automatic dues reconciliation · SMS/WhatsApp reminders · drill library and session planner · player stats leaderboards · "in-form" card variants (POTM boost) · multi-club SaaS onboarding.
