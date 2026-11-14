# Deploying FirstRow for free

Three free services, in this order:

1. **Neon**: PostgreSQL database
2. **Render**: the Spring Boot API, as a Docker web service
3. **Vercel**: the React frontend
4. Back on Render: allow the Vercel URL in `ALLOWED_ORIGINS`

Time needed: about 30–45 minutes. You need a GitHub account with this repo pushed to it.
Provider dashboards change their wording from time to time; if a label below doesn't match exactly,
look for the closest option.

---

## 0. Before you start

Generate a strong JWT secret (keep it somewhere safe, never commit it):

```bash
openssl rand -base64 48
# no openssl? use:  node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"
```

Pick an admin email and a **new, strong** admin password (not the local `admin-password-123`).

## 1. Neon (PostgreSQL)

1. Sign up at <https://neon.tech> and create a project. Choose **Postgres 16** and the region closest
   to where you'll run Render (e.g. both in Singapore or both in Frankfurt): every query crosses that distance.
2. Open **Connection details**. Turn **off** "Connection pooling" so you get the **direct** host
   (its hostname does *not* contain `-pooler`).
   - Why direct: Flyway runs migrations at startup, and the JDBC driver's prepared statements work best on a direct
     connection. Our own Hikari pool already pools connections.
3. Neon shows a URL like:

   ```
   postgresql://neondb_owner:AbC123xyz@ep-cool-name-123456.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
   ```

   Split it into the three values the backend expects:

   | Env var | Value |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://ep-cool-name-123456.ap-southeast-1.aws.neon.tech/neondb?sslmode=require` |
   | `DB_USERNAME` | `neondb_owner` |
   | `DB_PASSWORD` | `AbC123xyz` |

   Note the `jdbc:` prefix, no user/password in the URL, and keep `?sslmode=require`.

You don't create any tables: **Flyway** builds the schema and loads the demo events on the backend's first start.

Free-tier behaviour: Neon suspends the database after a few minutes without queries; the first query after
that takes a moment longer while it wakes up.

## 2. Render (backend API)

1. Sign up at <https://render.com> with GitHub.
2. **New → Web Service** → pick your repository.
3. Settings:

   | Setting | Value |
   |---|---|
   | Language / Runtime | **Docker** |
   | Root Directory | `backend` |
   | Dockerfile Path | `./Dockerfile` (relative to the root directory) |
   | Instance Type | **Free** |
   | Region | same as Neon |
   | Health Check Path (under Advanced) | `/actuator/health` |

4. **Environment variables:**

   | Name | Value |
   |---|---|
   | `DB_URL` | from step 1 |
   | `DB_USERNAME` | from step 1 |
   | `DB_PASSWORD` | from step 1 |
   | `DB_POOL_SIZE` | `5` (free databases allow few connections) |
   | `JWT_SECRET` | the secret from step 0 |
   | `ADMIN_EMAIL` | your admin email |
   | `ADMIN_PASSWORD` | your strong admin password |
   | `ALLOWED_ORIGINS` | `http://localhost:5173` for now (updated in step 4) |

   Don't set `PORT`: Render sets it, and the app already listens on `${PORT}`.

5. **Create Web Service.** Render builds the Docker image (several minutes the first time), then starts it.
   Watch the logs for:

   ```
   Successfully applied 3 migrations to schema "public", now at version v3
   admin.bootstrap created admin account you@example.com
   Started FirstRowApplication in ... seconds
   ```

6. Check it (replace with your URL):

   ```bash
   curl https://firstrow-api.onrender.com/actuator/health      # {"status":"UP",...}
   curl https://firstrow-api.onrender.com/api/v1/events        # the 4 demo events
   ```

   Swagger UI: `https://firstrow-api.onrender.com/swagger-ui.html`

### Cold starts (important for demos)

The free instance **sleeps after about 15 minutes without traffic**. The next request wakes it, which means
starting the JVM from zero on a small CPU share. I measured this locally by running the image with Render's
free-tier limits (`docker run --cpus=0.1 --memory=512m`):

| Backend image | Time until healthy |
|---|---|
| Default JVM flags | 329 s |
| Final image (C1-only JIT + CDS archive) | **114 s** |

So expect up to **about 2 minutes** for the first request after a sleep (Render may allow short CPU bursts,
so it can be faster). The frontend shows "It may be waking up; please try again in a minute" on network errors.
**Before a demo or interview, open the API URL a few minutes early.**

Memory: the app idles at about 176 MiB of the 512 MiB limit (same simulation).

## 3. Vercel (frontend)

1. Sign up at <https://vercel.com> with GitHub. **Add New → Project** → import the repository.
2. Settings:

   | Setting | Value |
   |---|---|
   | Root Directory | `frontend` |
   | Framework Preset | **Vite** (auto-detected) |
   | Build Command | `npm run build` |
   | Output Directory | `dist` |
   | Node.js version | 24.x (read from `engines` in `package.json`) |

3. Environment variable:

   | Name | Value |
   |---|---|
   | `VITE_API_URL` | your Render URL, e.g. `https://firstrow-api.onrender.com` (no trailing slash) |

   Vite bakes `VITE_*` variables into the JavaScript **at build time**. If you change it later, redeploy.

4. **Deploy.** You get a URL like `https://firstrow.vercel.app`. `vercel.json` sends every path to
   `index.html`, so reloading `/events/2` works.

## 4. Allow the frontend in CORS

Browsers block the Vercel page from calling the Render API until the API lists the page's origin.

1. Render → your service → **Environment** → set
   `ALLOWED_ORIGINS` = `https://firstrow.vercel.app` (exact origin: `https://`, no trailing slash, no path).
   To keep local development working too, use a comma-separated list:
   `https://firstrow.vercel.app,http://localhost:5173`.
2. Save; Render redeploys automatically.
3. Check:

   ```bash
   curl -s -o /dev/null -D - https://firstrow-api.onrender.com/api/v1/events \
        -H "Origin: https://firstrow.vercel.app" | grep -i access-control-allow-origin
   # access-control-allow-origin: https://firstrow.vercel.app
   ```

## 5. Smoke test the live site

1. Open the Vercel URL. Events appear (wait for the cold start if needed).
2. Sign up, open an event, select 2 seats, **Hold**, then **Pay**. The booking shows in **My bookings**.
3. Log in as the admin and create an event on the **Admin** page.
4. Put the live URL and the Swagger URL in the README.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Browser console: "blocked by CORS policy" | `ALLOWED_ORIGINS` doesn't exactly match the Vercel origin (scheme, trailing slash, `www`). |
| Frontend calls `localhost:8080` in production | `VITE_API_URL` wasn't set when Vercel built; set it and redeploy. |
| Render logs: `Connection refused` / `password authentication failed` | `DB_URL` missing `jdbc:` prefix, wrong host, or user/password left inside the URL. |
| Render logs: `SSL` errors | `?sslmode=require` missing from `DB_URL`. |
| App fails at start: `JWT_SECRET must be at least 32 bytes` | Secret too short; generate one as in step 0. |
| Render deploy marked failed while logs show the app starting | Slow cold start on the free tier; redeploy, and make sure the health check path is `/actuator/health`. |
| 401 right after logging in | Clock/secret mismatch after changing `JWT_SECRET`: log out and in again (old tokens are invalid). |

## What's public in this setup

- `/swagger-ui.html` and `/v3/api-docs` are public on purpose (portfolio demo).
- `/actuator/prometheus` is public so a hosted Prometheus could scrape it. It exposes only counts and
  timings, no personal data. In a real product, restrict it (separate management port, or require auth).
- Secrets live only in the Render dashboard. The repo contains only local-development defaults.
