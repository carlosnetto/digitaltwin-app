# Digital Twin App

A multicurrency fintech wallet prototype built on Matera's Digital Twin ledger. Supports fiat (USD, BRL) and stablecoin (USDC, USDT) accounts with live balances, real-time exchange rates, buy/sell/convert operations, P2P transfers between platform users, and a fully installable PWA experience.

**Live:** https://materalabs.us/digitaltwin-app

---

## What It Does

| Capability | Details |
|---|---|
| **Live balances** | Fetched from mini-core on every load; refreshable via button |
| **Buy crypto** | Pay with USD or BRL, receive USDC or USDT at live rate |
| **Sell crypto** | Sell USDC or USDT, receive USD or BRL at live rate |
| **Convert fiat** | Swap between USD and BRL at live rate |
| **P2P transfer** | Send any currency to another platform user by email — debit sender, credit recipient |
| **Live transactions** | Up to 50 most-recent transactions per account, enriched with i18n summary (e.g. "Sent 25.00 USD to John Doe") |
| **Transaction detail** | Tap any transaction to see a full detail modal with labeled fields resolved from metadata |
| **Account statements** | Generate a PDF (opens in native viewer) or Excel (.xlsx, downloaded) for any date range — streamed directly from the server, no temp files |
| **Exchange rates** | Refreshed every 10 minutes from open.er-api.com; stablecoin pairs locked at 1:1 |
| **Audit trail** | Every buy/sell/convert logs 4 mini-core tx IDs; every P2P logs 2 tx IDs + metadata blob |
| **Welcome credit** | New users receive 10,000 BRL (Cash Deposit, code 10001) on first login |
| **Settings** | Language (English / Português Brasil) and timezone selector (Brazil + US zones) |
| **PWA** | Installable on iOS, Android, and desktop Chrome |
| **Receive (prototype)** | Shows placeholder QR / address with a "PROTOTYPE" watermark — real camera/address wiring not yet implemented |

---

## Run Locally

**Prerequisites:** Node.js 18+, Java 21+, Maven, PostgreSQL (`global_banking_db` Docker container running), mini-core Flask server running on port 5001

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

---

## Architecture

```
User (browser / PWA)
  └── Cloudflare Worker (worker.ts)
        ├── /digitaltwin-app/api/* → strip prefix → proxy → Cloudflare Tunnel
        │     → digitaltwinapp-api.materalabs.us
        │     → cloudflared → localhost:8081
        │     → Spring Boot API (Java 21)
        │           ├── Google OAuth validation
        │           ├── Session management (JDBC / PostgreSQL)
        │           ├── Wallet + rate + conversion + P2P endpoints
        │           └── mini-core client (HTTP → localhost:5001)
        └── /digitaltwin-app/* → static assets (dist/) + SPA fallback
```

### Data stores

| Store | What lives there |
|---|---|
| `digitaltwinapp` schema (PostgreSQL) | Users, currencies, user_accounts, exchange_rates, conversions, p2p_transactions, transaction_codes, transaction_schemas, transaction_schema_i18n, transaction_metadata |
| `minicore` schema (PostgreSQL) | Accounts, transactions, balances (managed by mini-core Flask API) |
| `localStorage` (browser) | Wallet metadata cache (`dt_wallets`); timezone (`dt_timezone`); language (`dt_lang`) |

---

## Tech Stack

| Layer | Technology |
|---|---|
| UI | React 19 + TypeScript |
| Styling | Tailwind CSS v4 |
| Build | Vite 6 |
| Deployment | Cloudflare Workers + Static Assets |
| API | Spring Boot 3.3 / Java 21 |
| Auth | Google OAuth (implicit flow) + server-side domain + DB check |
| Database | PostgreSQL (`digitaltwinapp` schema, 20 Liquibase migrations) |
| PDF generation | OpenPDF 1.3.30 (LGPL) — streamed, no temp files |
| Excel generation | Apache POI 5.3.0 (`poi-ooxml`) — streamed, no temp files |
| Core Banking | Mini-Core (Flask, `localhost:5001`) |
| Tunnel | Cloudflare Tunnel (`digitaltwinapp-api.materalabs.us → localhost:8081`) |
| PWA | Web App Manifest + Service Worker |

---

## Project Structure

```
src/
  App.tsx           # All UI: Dashboard, TransactionDetailModal, modals (Buy/Sell/Convert/Send/Receive/Settings), nav, login
  store.tsx         # React Context: wallet state, localStorage cache, refreshWallets
  types.ts          # TypeScript types + TX transaction code constants
  main.tsx          # React DOM entry point
  index.css         # Tailwind v4 @theme (Matera design tokens) + animations
public/
  manifest.json     # PWA manifest
  sw.js             # Service worker (stale-while-revalidate)
  icon.svg          # App icon
  erd/              # SchemaSpy ERD output (digitaltwinapp schema)
api/                # Spring Boot Java API (port 8081)
  src/main/java/.../
    client/         # MiniCoreClient: account CRUD + transaction creation
    controller/     # AuthController, WalletController (wallets, convert, p2p, rate, transactions, detail, user lookup)
    listener/       # PostgresNotificationListener: pg_notify → provision accounts
    model/          # WalletDto, ConversionRequest, ConversionResultDto, P2pRequest
    service/        # Auth, WalletService (balances, transactions+metadata, rates, p2p), ConversionService,
                    # ExchangeRateService, UserAccountProvisioningService,
                    # SchemaRegistryService, TransactionDisplayService, TransactionMetadataBackfillService,
                    # StatementService (PDF via OpenPDF), ExcelStatementService (XLSX via Apache POI)
    config/         # WebConfig (CORS), SecurityConfig
  src/main/resources/
    db/changelog/   # 20 Liquibase migrations → digitaltwinapp schema
    application.yml
    application-local.yml  # gitignored — DB credentials
worker.ts           # Cloudflare Worker: API proxy + prefix strip + SPA fallback
wrangler.jsonc      # Cloudflare Worker config
tunnel-deploy.sh    # Starts cloudflared tunnel → localhost:8081
```

---

## Database Schema (`digitaltwinapp`)

| Migration | Table / Change |
|---|---|
| 001 | `CREATE SCHEMA digitaltwinapp` |
| 002 | `users` (id UUID, email, name, status, user_id BIGINT sequence) |
| 003 | Seed: `carlos.netto@matera.com` |
| 004 | Add sequential `user_id` to users |
| 005 | `currencies` (id, code, name, is_fiat, logo_url, decimal_places) |
| 006 | `user_accounts` (user_id ↔ currency_id ↔ minicore_account_id) |
| 007 | pg_notify trigger → auto-provision mini-core accounts on user INSERT |
| 008 | Add `is_fiat`, `logo_url` to currencies |
| 009 | Seed liquidity buffer (user_id=1, system counterparty for conversions) |
| 010 | `exchange_rates` (12 directional pairs; stablecoin pairs locked at 1.0) |
| 011 | `conversions` (buy/sell/convert audit: 4 mini-core tx IDs per record) |
| 012 | `p2p_transactions` (id, created_at TIMESTAMPTZ, amount, debit_tx_id, credit_tx_id) |
| 013 | Simplify p2p_transactions (drop redundant user/currency FKs) |
| 014 | `transaction_codes` — curated subset of mini-core codes with domain labels |
| 015 | `transaction_schemas` — JSON Schema (Draft 2020-12) per transaction code |
| 016 | `transaction_schema_i18n` — summary + detail display templates per code × language |
| 017 | Add external wallet send/receive codes (50004, 40004) |
| 018 | Seed JSON schemas for all 12 transaction codes |
| 019 | `transaction_metadata` — one row per ledger transaction, linked by `ledger_id` |
| 020 | Patch existing schemas: add `schema_version` as required first field |

### Account Number Formula
`account_number = user_id × 1000 + currency_id`

| Currency | currency_id | Example user_id=1003 |
|---|---|---|
| USD | 100 | 1003100 |
| BRL | 101 | 1003101 |
| USDC | 103 | 1003103 |
| USDT | 104 | 1003104 |

**Liquidity Buffer:** `user_id=1` → accounts `1100`, `1101`, `1103`, `1104`. Counterparty for all buy/sell/convert operations.

---

## API Endpoints

All endpoints require an active session (Google OAuth). Returns `401` if unauthenticated.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/google` | Exchange Google access token for session |
| `GET` | `/api/auth/me` | Return current session user |
| `POST` | `/api/auth/logout` | Destroy session |
| `GET` | `/api/wallets` | List user's wallets with live balances |
| `GET` | `/api/wallets/transactions?currencyCode=X&lang=en` | Up to 50 recent transactions enriched with i18n `summary` field |
| `GET` | `/api/wallets/transactions/{ledgerId}?lang=en` | Transaction detail — resolved `summary` + labeled `fields[]` |
| `GET` | `/api/wallets/rate?from=X&to=Y` | Live exchange rate from DB |
| `POST` | `/api/wallets/convert` | Buy / sell / convert (4 mini-core transactions) |
| `POST` | `/api/wallets/p2p` | P2P transfer to another platform user |
| `GET` | `/api/users/lookup?email=X` | Resolve email → name (for P2P confirmation) |
| `GET` | `/api/wallets/statement?currencyCode=X&from=Y&to=Z&lang=en` | Stream PDF statement (`Content-Disposition: inline`) |
| `GET` | `/api/wallets/statement/xlsx?currencyCode=X&from=Y&to=Z&lang=en` | Stream XLSX statement (`Content-Disposition: attachment`) |

---

## Transaction Codes (mini-core)

### Conversions (4 legs per operation)

| Operation | User debit | User credit | Pool credit | Pool debit |
|---|---|---|---|---|
| Buy (fiat→crypto) | 50005 Crypto Purchase Payment | 40003 Crypto Purchase | 10018 Internal Transfer In | 20021 Internal Transfer Out |
| Sell (crypto→fiat) | 50003 Crypto Sale | 40005 Crypto Sale Proceeds | 10018 Internal Transfer In | 20021 Internal Transfer Out |
| Convert (crypto↔crypto) | 50002 Crypto Conversion Sent | 40002 Crypto Conversion Received | 10018 Internal Transfer In | 20021 Internal Transfer Out |
| Convert (fiat↔fiat) | 50006 Currency Conversion Out | 40006 Currency Conversion In | 10018 Internal Transfer In | 20021 Internal Transfer Out |

### P2P Transfers (2 legs)

| Leg | Code | Description |
|---|---|---|
| Sender debit | 20026 | P2P Sent |
| Recipient credit | 10027 | P2P Received |

### Provisioning

| Event | Code | Description |
|---|---|---|
| New user welcome credit | 10001 | Cash Deposit (10,000 BRL) |

---

## Authentication

Login is Google OAuth restricted to `@matera.com` accounts:

1. Browser opens Google popup → receives access token
2. Token POSTed to `/digitaltwin-app/api/auth/google`
3. Worker proxies to Java API via Cloudflare Tunnel
4. Java validates token with Google, checks `@matera.com` domain, queries `digitaltwinapp.users`
5. Unknown `@matera.com` emails are auto-provisioned on first login
6. On new user: four mini-core accounts created via PostgreSQL `LISTEN/NOTIFY` trigger; 10,000 BRL welcome credit posted
7. Returns 200 (session cookie) or 403 (suspended/wrong domain)

---

## UI — Account Actions by Currency Type

| Action | Fiat (USD, BRL) | Crypto (USDC, USDT) |
|---|---|---|
| Receive | ✅ | ✅ |
| Send (P2P) | ✅ | ✅ |
| Buy | ❌ | ✅ |
| Sell | ❌ | ✅ |
| Convert | ✅ (fiat↔fiat) | ❌ |

### Send (P2P) Flow
1. Enter amount + recipient email → **Next**
2. API resolves email → recipient name displayed for confirmation
3. **Confirm** → debit sender (20026) + credit recipient (10027)
4. `p2p_transactions` row inserted; balance and transaction list auto-refreshed

---

## Frontend Caching

Wallet metadata (currency names, decimal places, account IDs) is cached in `localStorage` under `dt_wallets` after the first successful API call. On subsequent page loads, cached data is displayed instantly while the API fetches fresh balances in the background. Cache is cleared on logout.

Timezone preference is stored in `localStorage` under `dt_timezone`. Defaults to the browser's detected timezone. Transaction timestamps are converted to the selected timezone for display.

Language preference is stored in `localStorage` under `dt_lang` (BCP-47, e.g. `en`, `pt-BR`). Defaults to `en`. Passed as `&lang=` on all transaction API calls so summaries are returned in the user's chosen language.

---

## Amount Precision

Every currency carries its own `decimal_places` from the DB — no hardcoded fiat=2 / crypto=6 assumptions. All amounts are truncated with `RoundingMode.DOWN` at every layer:

**Frontend:** `sanitizeAmount(val, decimalsFor(currency, wallets))` is called on every keystroke. `decimalsFor` looks up `decimalPlaces` from the in-memory wallet cache. Inputs use `type="text" inputMode="decimal"` to give numeric keyboard on mobile without browser spinner arrows.

**API:** `ConversionService` and `WalletService.p2pTransfer` query `decimal_places` from the DB and call `BigDecimal.setScale(decimalPlaces, RoundingMode.DOWN)` before passing to mini-core. Amounts are always `BigDecimal` (never `double`) — `double` causes floating-point serialization artifacts (e.g. `5253.83924303` instead of `5253.83`) that mini-core rejects with 422.

| Currency | Decimal places |
|---|---|
| USD | 2 |
| BRL | 2 |
| USDC | 6 |
| USDT | 6 |

---

## Installing as a Mobile App (PWA)

- **iOS Safari:** Share → Add to Home Screen
- **Android Chrome:** Menu → Install App (or banner prompt)
- **Desktop Chrome:** Install icon in address bar

---

## Utility Scripts

| Script | Purpose |
|---|---|
| `listusers.sh` | List all users in `digitaltwinapp.users` |
| `listp2ptrans.sh` | List all P2P transactions with sender, recipient, amount, currency |
| `listbuysellconvert.sh` | List all buy/sell/convert conversions with user, currencies, amounts, and all 4 pool tx IDs |
| `transfer-data-pack.sh` | Pack DB dump + credential files into a timestamped zip for machine migration |
| `transfer-data-unpack.sh` | Unpack the zip on the target machine: restore DB + copy credentials |

---

See `CLAUDE.md` for architecture notes, `HISTORY.md` for decisions log, `MINICORE.md` for mini-core integration reference, and `CLOUDFLARE.md` for deployment details.
