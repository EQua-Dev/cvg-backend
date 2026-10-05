# Deploying CVG

| Part | Repo | Where |
|---|---|---|
| API + database | `cvg-backend` | Railway (Docker + PostgreSQL) |
| The Desk (management) | `cvg-admin` | Vercel |
| The Club (players) | `cvg-players` | Vercel |
| The Board (public site) | `cvg-public` | Vercel |

The web apps never call the API from the browser directly. Each one proxies `/api/*` to the backend (`CVG_API_URL`), so the session cookie is first-party on each site and no CORS setup is needed.

## Branches

- `main`: production. Railway's production environment and Vercel production deploy from it.
- `dev`: staging. Railway's `dev` environment and Vercel preview deployments build from it.

Work on a feature branch, merge into `dev` to try it on staging, then merge `dev` into `main` to release.

---

## 1. Backend and database on Railway

1. **New project → Deploy from GitHub repo → `cvg-backend`.** Railway reads `railway.json` and builds with the `Dockerfile`. The health check is `/actuator/health`.
2. **Add the database:** in the project, **+ New → Database → PostgreSQL**.
3. **Set variables** on the `cvg-backend` service (**Variables** tab):

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` (a reference to the database; the app converts it to JDBC itself) |
   | `CVG_BOOTSTRAP_ADMIN_PHONE` | Phone of the first admin, e.g. `0803 555 0192`. Only used while the club has no members |
   | `CVG_BOOTSTRAP_ADMIN_NAME` | Their full name |
   | `CVG_VERIFY_SECRET` | A long random string; signs ID card QR codes. Generate with `openssl rand -hex 32`. **Never change it after cards are printed** |
   | `CVG_CLUB_APP_URL` | The Club's address, e.g. `https://cvg-players.vercel.app` (used in WhatsApp join links) |
   | `CVG_PUBLIC_SITE_URL` | The Board's address, e.g. `https://cvg-public.vercel.app` (used in ID card QR codes) |

   `PORT` is set by Railway automatically. Leave `CVG_COOKIE_SECURE` unset (it defaults to `true`).
4. **Settings → Networking → Generate Domain.** Copy the URL (e.g. `https://cvg-backend-production.up.railway.app`). The web apps need it.
5. **Region:** Settings → Region → *EU West (Amsterdam)* is the closest to Abuja.
6. **Deploy.** On first start Flyway creates the tables and the first admin is created. Sign in on the Desk with that phone and the last 4 digits as the passcode, then set a new passcode.

**Staging:** in Railway, **Environments → New Environment → `dev`** (it copies the services). In the `dev` environment set the backend service's source branch to `dev`, give it its own Postgres and its own variables (use the Vercel preview URLs), and generate a domain.

## 2. Web apps on Vercel (do this three times)

For each of `cvg-admin`, `cvg-players` and `cvg-public`:

1. **Add New → Project → import the repo.** Vercel detects Next.js; `vercel.json` sets the region to Paris (`cdg1`), close to the Railway backend.
2. **Environment variables:**

   | Variable | Production | Preview |
   |---|---|---|
   | `CVG_API_URL` | The Railway production URL | The Railway `dev` URL |

   `CVG_API_URL` is read when the app is **built**, so redeploy after changing it.
3. **Settings → Git:** production branch `main`. Pushes to `dev` get preview deployments. To give staging a fixed address, go to **Settings → Domains** and assign a domain to the `dev` branch.
4. Deploy, then put the three production URLs into the backend's `CVG_CLUB_APP_URL` and `CVG_PUBLIC_SITE_URL` on Railway.

Optional custom domains, e.g. `desk.cvgfc.ng`, `club.cvgfc.ng`, `cvgfc.ng`: add them under each Vercel project's **Settings → Domains**, then update the two backend URLs.

---

## 3. Run everything locally with Docker

Needs Docker Desktop (or Docker Engine with Compose v2). Clone the four repos **side by side**:

```bash
mkdir cvg && cd cvg
git clone https://github.com/EQua-Dev/cvg-backend.git
git clone https://github.com/EQua-Dev/cvg-admin.git
git clone https://github.com/EQua-Dev/cvg-players.git
git clone https://github.com/EQua-Dev/cvg-public.git
# optional: test the staging branch instead
# for r in cvg-backend cvg-admin cvg-players cvg-public; do git -C $r checkout dev; done

cd cvg-backend
docker compose up --build
```

The first build takes a few minutes. Then open:

| App | URL |
|---|---|
| The Desk (management) | http://localhost:3001 |
| The Club (players) | http://localhost:3002 |
| The Board (public) | http://localhost:3000 |
| API health | http://localhost:8080/actuator/health |
| Postgres (for a DB tool) | `localhost:55432`, database/user/password `cvg` |

Sign in to the Desk with **0803 555 0192** and passcode **0192** (the last 4 digits). To use your own number:

```bash
CVG_ADMIN_PHONE="0801 234 5678" CVG_ADMIN_NAME="Your Name" docker compose up --build
```

The admin is only created while the database is empty, so after changing it run `docker compose down -v` first.

Handy commands:

```bash
docker compose up --build -d          # run in the background
docker compose logs -f api            # follow the API logs
docker compose up --build db api      # only the database and API (run the web apps with npm run dev)
docker compose down                   # stop, keep the data
docker compose down -v                # stop and wipe the database
```

**"Port is already allocated"?** Something on your machine already uses that port. Either stop it (`lsof -nP -iTCP:<port> -sTCP:LISTEN` shows what), or move CVG to another port, then run `docker compose down` and start again:

```bash
CVG_DB_PORT=55433 CVG_DESK_PORT=4001 docker compose up --build
```

| Variable | Default |
|---|---|
| `CVG_DB_PORT` | 55432 |
| `CVG_API_PORT` | 8080 |
| `CVG_DESK_PORT` | 3001 |
| `CVG_CLUB_PORT` | 3002 |
| `CVG_PUBLIC_PORT` | 3000 |

If you move the Club or public site, WhatsApp join links and ID-card QR codes will still point at 3002 and 3000. That's fine for testing on your computer.

To test on your phone over Wi-Fi, open `http://<your-computer's-IP>:3002`.
