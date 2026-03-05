# CLAUDE.md — Digital Twin App

## Project Context

A multicurrency mobile wallet prototype demonstrating Matera's Digital Twin ledger concept. Supports USD, BRL (fiat) and USDC, USDT (crypto) wallets. Deployed as a PWA at `materalabs.us/digitaltwin-app`.

Originally scaffolded from a Google AI Studio template. Gemini API removed.

## Architecture

### Frontend (PWA)
```
src/
  App.tsx       # All UI: Dashboard, WalletCard, TransactionList, modals, QRScanner, BottomNav, Sidebar, Login
  store.tsx     # React Context: wallet state, transaction list, sendFunds, generateReceiveDetails
  types.ts      # Wallet, Transaction interfaces + TX constants (transaction codes from mini-core)
  main.tsx      # ReactDOM.createRoot entry
  index.css     # Tailwind v4 @theme block + custom @keyframes (qr-scan)
public/
  manifest.json # PWA manifest
  sw.js         # Service worker
  icon.svg      # App icon
worker.ts       # Cloudflare Worker: API proxy + prefix strip + SPA fallback
wrangler.jsonc  # Cloudflare config
tunnel-deploy.sh  # Starts cloudflared → localhost:8081 (requires .tunnel-token)
```

### API (Spring Boot — port 8081)
```
api/
  src/main/java/.../
    controller/AuthController.java   # POST /api/auth/google, GET /api/auth/me, POST /api/auth/logout
    service/GoogleAuthService.java   # access_token → Google userinfo → domain check → DB check
    config/WebConfig.java            # CORS: allowed origins from app.allowed-origins
  src/main/resources/
    db/changelog/
      001-create-schema.xml          # CREATE SCHEMA digitaltwinapp
      002-create-users.xml           # digitaltwinapp.users (id, email, name, status)
      003-seed-users.xml             # carlos.netto@matera.com as active
    application.yml                  # port 8081, Liquibase, CORS (includes https://materalabs.us)
    application-local.yml            # gitignored — DB credentials
    application-local.yml.example    # template for new devs
```

**Run:** `cd api && mvn spring-boot:run -Dspring-boot.run.profiles=local`

**Auth flow:**
1. Browser Google popup → access token
2. `POST ${import.meta.env.BASE_URL}api/auth/google` → Worker proxies to tunnel → Java
3. Java: calls Google userinfo API → validates `@matera.com` domain → queries `digitaltwinapp.users`
4. Returns `200` + session cookie, or `403` (not provisioned / suspended)

**Database:** `digitaltwinapp` schema in `banking_system` PostgreSQL (`global_banking_db` Docker).
Credentials in `application-local.yml` (gitignored). Liquibase runs automatically on startup.

### Backoffice (Spring Boot — port 8080)
```
backoffice/
  src/main/java/.../
    adaptor/solana/   # SolanaAdaptorImpl — HD derive, balances, send SOL/token, history
    adaptor/circle/   # CircleMintAdaptorImpl — gated behind circle.enabled=false
    controller/       # WalletController: POST /api/wallets, POST /api/wallets/send
    service/          # WalletProvisioningService, TransactionProvisioningService,
                      # WalletMonitoringService (@Scheduled), MonitoringPersistenceService
    repository/       # JDBC repositories for all blockchain_schema tables
    startup/          # SeedLoader — prompts operator for mnemonic at startup
  src/main/resources/
    db/changelog/     # 9 Liquibase migrations → blockchain_schema in PostgreSQL
    application.yml   # Solana RPC, Circle, monitoring config
  docker-compose.yml  # SchemaSpy ERD generator (run: docker-compose run schemaspy)
  docs/erd/           # Generated SchemaSpy HTML ERD (gitignored)
  SOLANA.md           # Full Solana + provisioning layer developer guide
  TODO.md             # Backoffice task backlog
```

Solana SDK: `software.sava` (Solana Foundation endorsed). Do NOT use `solanaj`.
Database: `blockchain_schema` in PostgreSQL (`global_banking_db` Docker container).
Mnemonics: RAM only via `SeedGroupRegistry` — never written to disk or DB.

## Key Patterns

### State
All state lives in `store.tsx` via React Context (`StoreProvider`). No external state library. Components read state via `useStore()`.

### Transaction Codes
`types.ts` exports a `TX` constants object mapping semantic names to `{ code: number, label: string }` from the mini-core system (`005-seed-data.xml`). Always use `TX.SOME_CODE.code` and `TX.SOME_CODE.label` when creating transactions — never hardcode raw numbers or labels.

```typescript
// Good
{ transactionCode: TX.DIRECT_DEPOSIT_PAYROLL.code, description: TX.DIRECT_DEPOSIT_PAYROLL.label }

// Bad
{ transactionCode: 10006, description: 'Direct Deposit - Payroll' }
```

### Sub-path Asset References
The app is served under `/digitaltwin-app/`. Any reference to `public/` assets in JSX must use `import.meta.env.BASE_URL`:

```tsx
// Good
src={`${import.meta.env.BASE_URL}assets/Circle_USDC_Logo.svg.png`}

// Bad — breaks under sub-path
src="/assets/Circle_USDC_Logo.svg.png"
```

### API Calls from the Frontend
All API calls must use `import.meta.env.BASE_URL` as prefix to scope them under `/digitaltwin-app/`:

```typescript
// Good — scoped, works in dev (Vite proxy) and prod (Worker proxy)
fetch(`${import.meta.env.BASE_URL}api/auth/google`, { method: 'POST', ... })

// Bad — /api/google conflicts with other apps sharing the domain
fetch('/api/auth/google', ...)
```

The Vite dev server proxies `/digitaltwin-app/api/*` → `http://localhost:8081`. The Cloudflare Worker does the same in production via tunnel.

### Frontend vs API vs Backoffice
- **Frontend (`src/`)** — SPA with local seed state. Login talks to the API; wallet UI is still mock data.
- **API (`api/`)** — Spring Boot on port 8081. Handles auth (Google OAuth + DB gate). Connected to frontend.
- **Backoffice (`backoffice/`)** — Spring Boot on port 8080. Manages on-chain Solana wallets. Not yet connected to the frontend.

## Design System

Custom Tailwind colors defined in `src/index.css` `@theme` block:

| Token | Value | Usage |
|---|---|---|
| `matera-blue` | `#001E60` | Cards, gradients |
| `matera-blue-dark` | `#001133` | Dark accents |
| `matera-green` | `#00E5FF` | Primary actions, icons, scan button |
| `matera-green-dark` | `#00B3CC` | Hover states |
| `matera-bg` | `#0A0F1C` | Page background |
| `matera-card` | `#131B2F` | Card backgrounds, nav |
| `matera-text` | `#E2E8F0` | Body text |
| `matera-muted` | `#94A3B8` | Secondary text, inactive nav |

Do NOT rename these tokens — they are used throughout App.tsx (100+ references).

## Wallets (seed data)

| ID | Currency | Type | Balance |
|---|---|---|---|
| 3 | USDC | crypto | 250.00 |
| 4 | USDT | crypto | 100.00 |
| 2 | USD | fiat | 500.00 |
| 1 | BRL | fiat | 1500.50 |

Transactions: 30 seed entries across all 4 wallets using real mini-core transaction codes.

## QR Scanner

`QRScannerModal` in `App.tsx` — currently a simulated viewfinder (no real camera). State is managed in the top-level `App` component (`showScanner` / `setShowScanner`) and passed as `onScanPress` prop to `BottomNav`.

To wire real camera scanning:
1. Use `jsqr` or `@zxing/library` inside `QRScannerModal`
2. Request `navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })`
3. Decode frames and route based on detected QR format (Pix, X9.150, EVM/Solana address, etc.)

Requires HTTPS — already satisfied by Cloudflare deployment.

## Deployment

```bash
npm run dev       # local dev, http://localhost:3000
npm run build     # builds to dist/ with base: '/digitaltwin-app/'
npm run deploy    # build + wrangler deploy
```

See `CLOUDFLARE.md` for account, routes, and deployment details.

## What NOT to Change

- CSS color variable names (`--color-matera-*`) — 100+ usages in App.tsx
- Wallet IDs in seed data — referenced in transaction `walletId` fields
- `base: '/digitaltwin-app/'` in `vite.config.ts` — breaks sub-path routing if removed
- `"binding": "ASSETS"` in `wrangler.jsonc` — required; without it `env.ASSETS` is undefined and worker crashes with error 1101
- Both route entries in `wrangler.jsonc` (with and without `/*`) — the exact match handles `/digitaltwin-app` without trailing slash

## Leftover from Template

`express` and `better-sqlite3` remain in `package.json` dependencies (leftover from the AI Studio template). They are not imported anywhere in the frontend source. Safe to remove if you want a cleaner dependency tree, but they don't affect the build or bundle size (Vite tree-shakes them out).
