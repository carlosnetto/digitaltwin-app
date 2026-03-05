# Digital Twin App

A multicurrency mobile wallet prototype built on Matera's Digital Twin ledger. Supports fiat (USD, BRL) and crypto (USDC, USDT) wallets with real transaction type codes, QR payment scanning, and a fully installable PWA experience.

**Live:** https://materalabs.us/digitaltwin-app

---

## Run Locally

**Prerequisites:** Node.js 18+, Java 21+, Maven, PostgreSQL (`global_banking_db` Docker container running)

**Terminal 1 — Java API:**
```bash
cd api
mvn spring-boot:run -Dspring-boot.run.profiles=local
# Runs on http://localhost:8081
```

**Terminal 2 — Frontend:**
```bash
npm install
npm run dev        # http://localhost:3000
```

The Vite dev server proxies `/digitaltwin-app/api/*` to `localhost:8081` automatically — no tunnel needed locally.

## Build & Deploy

```bash
# Frontend (Cloudflare Worker)
npm run deploy     # build + wrangler deploy

# Tunnel (exposes Java API via stable public hostname)
./tunnel-deploy.sh  # requires .tunnel-token (gitignored)
```

Deployed to `materalabs.us/digitaltwin-app` via Cloudflare Workers (route-based, no custom domain needed).
API exposed at `digitaltwinapp-api.materalabs.us` via Cloudflare Tunnel.
See `CLOUDFLARE.md` for full deployment and account details.

## Tech Stack

| Layer | Technology |
|---|---|
| UI | React 19 + TypeScript |
| Styling | Tailwind CSS v4 |
| Build | Vite 6 |
| Deployment | Cloudflare Workers + Static Assets |
| API | Spring Boot 3.3 / Java 21 |
| Auth | Google OAuth (implicit flow) + server-side domain + DB check |
| Database | PostgreSQL (`digitaltwinapp` schema) via Liquibase |
| Tunnel | Cloudflare Tunnel (`digitaltwinapp-api.materalabs.us → localhost:8081`) |
| PWA | Web App Manifest + Service Worker |

## Project Structure

```
src/
  App.tsx           # All UI components + routing + nav + Google login
  store.tsx         # React Context state: wallets, transactions, actions
  types.ts          # TypeScript types + TX transaction code constants
  main.tsx          # React DOM entry point
  index.css         # Tailwind theme + custom animations
public/
  manifest.json     # PWA manifest
  sw.js             # Service worker (stale-while-revalidate)
  icon.svg          # App icon (DT mark, brand colors)
api/                # Spring Boot Java API (port 8081)
  src/main/java/.../
    controller/     # AuthController: /api/auth/google, /me, /logout
    service/        # GoogleAuthService: token → Google userinfo → DB check
    config/         # WebConfig (CORS), SecurityConfig
  src/main/resources/
    db/changelog/   # 3 Liquibase migrations → digitaltwinapp schema
    application.yml
    application-local.yml  # gitignored — DB credentials
worker.ts           # Cloudflare Worker: API proxy + prefix strip + SPA fallback
wrangler.jsonc      # Cloudflare Worker config
tunnel-deploy.sh    # Starts cloudflared tunnel → localhost:8081
```

## Authentication

Login is Google OAuth restricted to `@matera.com` accounts. The flow:

1. Browser opens Google popup → receives access token
2. Token POSTed to `/digitaltwin-app/api/auth/google`
3. Worker proxies to Java API via Cloudflare Tunnel
4. Java calls Google userinfo, checks `@matera.com` domain, then queries `digitaltwinapp.users` table
5. Returns 200 (session cookie) or 403 (not provisioned / suspended)

## Installing as a Mobile App (PWA)

- **iOS Safari:** Share → Add to Home Screen
- **Android Chrome:** Menu → Install App (or banner prompt)
- **Desktop Chrome:** Install icon in address bar

See `CLAUDE.md` for architecture notes and `HISTORY.md` for decisions log.
