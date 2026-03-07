# CLAUDE.md — Digital Twin App

## Project Context

A multicurrency mobile wallet prototype demonstrating Matera's Digital Twin ledger concept. Supports USD, BRL (fiat) and USDC, USDT (crypto) wallets. Deployed as a PWA at `materalabs.us/digitaltwin-app`.

Originally scaffolded from a Google AI Studio template. Gemini API removed.

## Architecture

### Frontend (PWA)
```
src/
  App.tsx       # All UI: Dashboard, WalletCard, TransactionList, TransactionDetailModal, modals, QRScanner, BottomNav, Sidebar, Login
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
    client/MiniCoreClient.java       # getAccount, createAccount, createTransaction → localhost:5001
    controller/AuthController.java   # POST /api/auth/google, GET /api/auth/me, POST /api/auth/logout
    controller/WalletController.java # GET /api/wallets, transactions, rate, convert, p2p, statement (PDF+XLSX); GET /api/users/lookup
    listener/PostgresNotificationListener.java  # pg_notify daemon → provisions accounts on new user
    model/                           # UserInfo, WalletDto, ConversionRequest, ConversionResultDto, P2pRequest
    service/GoogleAuthService.java   # token → userinfo → domain check → DB check; auto-inserts new users
    service/WalletService.java       # balances, transactions (with metadata enrichment + 5min cache), rates, P2P transfer
    service/UserAccountProvisioningService.java # creates mini-core accounts + 10k BRL welcome credit; 60s catch-all @Scheduled
    service/ExchangeRateService.java # @Scheduled every 10min from open.er-api.com
    service/ConversionService.java   # 4 mini-core transactions + conversions table record
    service/StatementService.java    # PDF statement via OpenPDF 1.3.30 — streamed directly to OutputStream, no temp file
    service/ExcelStatementService.java # XLSX statement via Apache POI 5.3.0 — streamed directly to OutputStream, no temp file
    service/SchemaRegistryService.java          # pre-compiled networknt JSON schemas loaded at @PostConstruct; volatile swap
    service/TransactionDisplayService.java      # i18n template cache; en eager-loaded, others lazy; ${path} placeholder resolution
    service/TransactionMetadataBackfillService.java  # ApplicationRunner; backfills transaction_metadata for historical txs
    config/WebConfig.java            # CORS: allowed origins from app.allowed-origins
  src/main/resources/
    db/changelog/
      001-create-schema.xml          # CREATE SCHEMA digitaltwinapp
      002-create-users.xml           # users (id UUID, email, name, status, user_id BIGINT)
      003-seed-users.xml             # carlos.netto@matera.com as active (user_id=1000)
      004-add-user-id.xml            # sequential user_id column; seq starts 1003
      005-create-currencies.xml      # currencies: USD=100, BRL=101, USDC=103, USDT=104
      006-create-user-accounts.xml   # user_accounts (user_id, currency_id, minicore_account_id)
      007-user-created-notify-trigger.xml  # pg_notify('user_created') on INSERT
      008-currencies-add-is-fiat-logo.xml  # is_fiat BOOLEAN, logo_url VARCHAR, decimal_places INT
      009-seed-liquidity-buffer.xml  # system account user_id=1, liquidity-buffer@system
      010-create-exchange-rates.xml  # 12 directional pairs; stablecoin pairs locked at 1.0
      011-create-conversions.xml     # conversions table with 4 tx IDs
      012-create-p2p-transactions.xml # p2p_transactions (id, created_at TIMESTAMPTZ, amount, debit_tx_id, credit_tx_id)
      013-simplify-p2p-transactions.xml # drop redundant user/currency FKs from p2p_transactions
      014-create-transaction-codes.xml    # transaction_codes: curated subset of mini-core codes
      015-create-transaction-schemas.xml  # transaction_schemas: JSON Schema (Draft 2020-12) per code
      016-create-transaction-schema-i18n.xml  # transaction_schema_i18n: summary + detail templates per code × lang
      017-add-external-wallet-codes.xml   # adds codes for external wallet send/receive (50004, 40004)
      018-seed-transaction-schemas.xml    # seeds JSON schemas for all 12 codes (schema_version first field)
      019-create-transaction-metadata.xml # transaction_metadata: one row per ledger tx, linked by ledger_id
      020-add-schema-version-to-metadata-blobs.xml  # patches existing schemas to add schema_version as required field
    application.yml                  # port 8081, Liquibase, CORS (includes https://materalabs.us)
    application-local.yml            # gitignored — DB credentials
    application-local.yml.example    # template for new devs
    matera-logo-statement.png        # black Matera logo used in PDF statements (loaded from classpath)
  TRANSACTION-METADATA.md  # full system doc: DB tables, schemas, i18n, caching layers, API, backfill, versioning
  JAVA.md                  # Java coding conventions for this project
  SPRING.md                # Spring Boot conventions for this project
  TODO.md                  # API task backlog (ownership checks, API keys, forward metadata capture, etc.)
```

**Run:** `cd api && mvn spring-boot:run -Dspring-boot.run.profiles=local`

**Auth flow:**
1. Browser Google popup → access token
2. `POST ${import.meta.env.BASE_URL}api/auth/google` → Worker proxies to tunnel → Java
3. Java: calls Google userinfo API → validates `@matera.com` domain → queries `digitaltwinapp.users`
4. If not found and domain matches → auto-inserts user as `active`
5. Returns `200` + session cookie, or `403` (suspended)

**Database:** `digitaltwinapp` schema in `banking_system` PostgreSQL (`global_banking_db` Docker).
Credentials in `application-local.yml` (gitignored). Liquibase runs automatically on startup.

**CORS:** Non-secret production origins (`https://materalabs.us`) must be in `application.yml`, NOT only in `application-local.yml` — profile files only load when explicitly activated.

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

### Financial Invariants (always enforce before posting to mini-core)

Two rules that must hold for every operation that debits a user account:

1. **Sufficient balance** — fetch `available_balance` via `MiniCoreClient.getAccount()` and compare against the debit amount *before* calling `createTransaction`. Mini-core has no negative-balance guard; it will happily overdraft. Both `ConversionService` and `WalletService.p2pTransfer` do this check.

2. **Exact decimal precision** — always pass `BigDecimal` (never `double`) to `MiniCoreClient.createTransaction()`. Amounts must be truncated with `RoundingMode.DOWN` to the currency's `decimal_places` from the DB before the call. Mini-core enforces this and returns 422 on violation. Passing a `double` risks floating-point serialization producing extra digits (e.g. `5253.83924303` instead of `5253.83`).

Do **not** hardcode decimal places by currency type (fiat=2, crypto=6). Always read from `decimal_places` — different fiat currencies have different precision (e.g. JPY=0).

### Amount Input Validation (frontend)

All amount inputs use `type="text" inputMode="decimal"` (not `type="number"`) to avoid browser spinner arrows and allow precise keystroke-level control.

Two helpers in `App.tsx` enforce precision:

```typescript
// Strips non-numeric chars, enforces one decimal point, truncates at decimalPlaces
function sanitizeAmount(val: string, decimalPlaces: number): string {
  let s = val.replace(/[^0-9.]/g, '');
  const dot = s.indexOf('.');
  if (dot !== -1) {
    s = s.slice(0, dot + 1) + s.slice(dot + 1).replace(/\./g, '');
    s = s.slice(0, dot + 1 + decimalPlaces);
  }
  return s;
}

// Looks up decimal_places from the in-memory wallet cache (no hardcoded fiat/crypto assumption)
function decimalsFor(currency: string, wallets: WalletType[]): number {
  return wallets.find(w => w.currency === currency)?.decimalPlaces ?? 0;
}
```

Every `onChange` handler calls `sanitizeAmount(val, decimalsFor(currency, wallets))`. The `wallets` list comes from `useStore()` and already has `decimalPlaces` from the API.

### Rate Fetch Error Handling (frontend)

Rate fetches can fail (network, tunnel outage). Never silently swallow `.catch(() => {})` — users will see "Loading rate…" forever with no way to recover.

Pattern used in all three modals (ConvertModal, BuyModal, SellModal):

```typescript
const [rateError, setRateError] = useState(false);
const [rateKey, setRateKey] = useState(0);  // incrementing triggers retry

useEffect(() => {
  setRate(null);
  setRateError(false);
  fetch(...)
    .then(r => { if (!r.ok) throw new Error(); return r.json(); })
    .then(data => {
      const parsed = parseFloat(data.rate);
      if (!isNaN(parsed)) setRate(parsed);
      else setRateError(true);
    })
    .catch(() => setRateError(true));
}, [fromCurrency, toCurrency, rateKey]);  // rateKey in deps = retry mechanism
```

Rate display is tri-state: rate shown / error+retry button / loading spinner. Never show the Confirm button while `rate === null`.

### Language & Timezone Preferences (frontend)

Both are stored in `localStorage` and follow the same pattern:

```typescript
const [lang, setLang] = useState(() => localStorage.getItem('dt_lang') ?? 'en');
const handleLangChange = (l: string) => { setLang(l); localStorage.setItem('dt_lang', l); };
```

Keys: `dt_lang` (BCP-47, e.g. `'en'`, `'pt-BR'`), `dt_timezone` (IANA, e.g. `'America/Sao_Paulo'`).
`lang` is passed down to `Dashboard` and included as `&lang=` on all transaction API calls.
User changes it via the language `<select>` in `SettingsModal`.

### Account Statement Generation

Two services stream statements directly to `HttpServletResponse.getOutputStream()` — no temp files at any point.

**PDF** (`StatementService`, OpenPDF 1.3.30):
- `Content-Disposition: inline` → browser/mobile OS opens native PDF viewer
- Frontend: `blob()` → `URL.createObjectURL(blob)` → `window.open(url, '_blank')`
- Logo loaded from classpath: `getClass().getResourceAsStream("/matera-logo-statement.png")`
- Uses `java.awt.Color` — **not** `BaseColor` (removed in OpenPDF 1.3.30)

**Excel** (`ExcelStatementService`, Apache POI 5.3.0 `poi-ooxml`):
- `Content-Disposition: attachment` → browser triggers OS download handler
- Frontend: `blob()` → `URL.createObjectURL(blob)` → hidden `<a download>` click
- No logo in XLSX (embedded picture APIs in POI produce unreliable sizing results)

Both services share the same data pipeline: `resolveAccount` (DB query) → `miniCoreClient.getTransactions()` → filter by `effective_date` range → `fetchSummaries` (batch metadata query → i18n resolve) → format output.

**`StatementModal`** (frontend): period selector (last 15/30/90 days or custom date range) + PDF/Excel format toggle. The Generate button label and header icon update to match the selected format.

### PROTOTYPE Watermark

`ReceiveModal` (and any other modal showing placeholder UI) uses an absolute overlay:

```tsx
<div className="pointer-events-none absolute inset-0 flex items-center justify-center z-10">
  <span className="text-red-500/40 text-6xl font-black tracking-widest uppercase rotate-[-30deg] select-none">
    PROTOTYPE
  </span>
</div>
```

`pointer-events-none` ensures the watermark does not block interaction. Apply to any modal that shows simulated data (QR codes, receive addresses) until real implementation exists.

### Frontend vs API vs Backoffice
- **Frontend (`src/`)** — SPA. Fetches live wallet balances and drives buy/sell conversions via the API.
- **API (`api/`)** — Spring Boot on port 8081. Auth, live balances, exchange rates, conversions. Connected to frontend and mini-core.
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

## Currencies & Wallets

Currencies are defined in `digitaltwinapp.currencies`. IDs start at 100 to avoid collision with mini-core IDs.

| id  | code | name           | is_fiat | logo_url |
|-----|------|----------------|---------|----------|
| 100 | USD  | US Dollar      | true    | —        |
| 101 | BRL  | Brazilian Real | true    | —        |
| 103 | USDC | USD Coin       | false   | `assets/Circle_USDC_Logo.svg.png` |
| 104 | USDT | Tether         | false   | `assets/tether-usdt-logo.svg` |

Wallet balances are live — fetched from mini-core on every page load and after each conversion. There is no seed balance data in the frontend; balances come from `GET /api/wallets` → `WalletService` → `MiniCoreClient.getAccount()`.

**Liquidity Buffer:** system account `user_id=1`, `email=liquidity-buffer@system`. Has one mini-core account per currency. Serves as the counterparty for all buy/sell conversions. Cannot log in (email fails `@matera.com` domain check).

**Account number formula:** `user_id * 1000 + currency_id` (e.g., user 1000 + USD 100 = account `1000100`)

## Exchange Rates

`digitaltwinapp.exchange_rates` holds all 12 directional pairs. Stablecoin pairs (USDC↔USD, USDT↔USD, USDC↔USDT) have `is_stablecoin_pair=true` and are never updated. Non-stablecoin pairs refresh every 10 minutes from `open.er-api.com` (free, no API key).

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/google` | Validate Google token, create session |
| `GET` | `/api/auth/me` | Current session user |
| `POST` | `/api/auth/logout` | Destroy session |
| `GET` | `/api/wallets` | Live balances for all user accounts |
| `GET` | `/api/wallets/transactions?currencyCode=X&lang=en` | Up to 50 recent transactions, enriched with i18n `summary` field |
| `GET` | `/api/wallets/transactions/{ledgerId}?lang=en` | Single transaction detail — resolved `summary` + labeled `fields[]` |
| `GET` | `/api/wallets/rate?from=X&to=Y` | Live exchange rate |
| `POST` | `/api/wallets/convert` | Buy / sell / convert (4 mini-core txs) |
| `POST` | `/api/wallets/p2p` | P2P transfer to another platform user |
| `GET` | `/api/users/lookup?email=X` | Resolve email → name (P2P confirmation step) |
| `GET` | `/api/wallets/statement?currencyCode=X&from=Y&to=Z&lang=en` | Stream PDF statement (`Content-Disposition: inline`) |
| `GET` | `/api/wallets/statement/xlsx?currencyCode=X&from=Y&to=Z&lang=en` | Stream XLSX statement (`Content-Disposition: attachment`) |

`lang` defaults to `"en"`. Currently seeded languages: `en`, `pt-BR`. Falls back to `en` if the requested language has no templates for a given transaction code.

## Buy/Sell/Convert

`POST /api/wallets/convert` — body: `{ fromCurrencyCode, toCurrencyCode, fromAmount }`

Operation type detected from `is_fiat` flags. Four mini-core transactions per conversion:

| Operation | User debit | User credit | Pool credit | Pool debit |
|---|---|---|---|---|
| Buy (fiat→crypto) | 50005 | 40003 | 10018 | 20021 |
| Sell (crypto→fiat) | 50003 | 40005 | 10018 | 20021 |
| Convert (crypto↔crypto) | 50002 | 40002 | 10018 | 20021 |
| Convert (fiat↔fiat) | 50006 | 40006 | 10018 | 20021 |

Before posting: `ConversionService` checks `available_balance >= fromAmount` via `MiniCoreClient.getAccount()` — insufficient balance returns 400. All amounts are passed as `BigDecimal` (truncated with `RoundingMode.DOWN` to `decimal_places`). All four tx IDs plus the applied rate are recorded in `digitaltwinapp.conversions`.

## P2P Transfers

`POST /api/wallets/p2p` — body: `{ recipientEmail, currencyCode, amount }`

1. Resolves sender and recipient from DB; validates active status, shared currency account, sufficient balance
2. Posts debit (20026 P2P Sent) on sender, credit (10027 P2P Received) on recipient
3. Inserts into `digitaltwinapp.p2p_transactions` (id, created_at TIMESTAMPTZ, amount, debit_tx_id, credit_tx_id)

`GET /api/users/lookup?email=X` — used by the frontend before the confirmation step to show the recipient's name. Returns `{ name }` or 404.

## Transaction Metadata

Every transaction in mini-core carries only raw financial facts (amount, date, code). Domain context — who you sent to, what was converted at what rate, which blockchain — is stored in `digitaltwinapp.transaction_metadata` and linked by `ledger_id` (the mini-core `transaction_id` as string).

Full system documented in `api/TRANSACTION-METADATA.md`. Key points:

- **`transaction_schemas`** — one JSON Schema (Draft 2020-12) per transaction code, enforcing the metadata blob shape. Pre-compiled by `SchemaRegistryService` at startup.
- **`transaction_schema_i18n`** — `summary_data` (single string template) and `detailed_data` (labeled fields) per code × language. Resolved at runtime by `TransactionDisplayService` using `${dot.path}` placeholders against the metadata blob.
- **Self-contained blobs** — every blob starts with `"schema_version": 1` as its first key (enforced via `LinkedHashMap` in `insertMetadata()`). A blob forwarded to an external ledger is fully self-describing without access to our internal tables.
- **Caching** — three layers: `SchemaRegistryService` (compiled schemas, startup), `TransactionDisplayService` (parsed templates, lazy per language), `WalletService.metadataCache` (raw metadata blobs, 5-min TTL, language-agnostic).
- **Transaction list** — `GET /api/wallets/transactions` batch-fetches metadata for all returned IDs in one `IN` query and returns an optional `summary` string per transaction. Frontend shows `summary` if present, falls back to `transaction_description`.
- **Transaction detail** — `GET /api/wallets/transactions/{ledgerId}` returns `{ summary, fields: [{ label, value }] }`. Frontend opens `TransactionDetailModal` on tap; cache-first so common case is zero DB queries.

### Liquibase: modifying already-applied changesets

Never edit a changeset that has already been applied to any environment — Liquibase stores its checksum and will refuse to start if the file content changes. Two safe options:

1. **`<validCheckSum>`** — add the old checksum to the changeset to accept both the historical DB state and the new file. Use when the SQL was already executed and the data change is handled separately (e.g. a follow-up migration).
2. **New migration** — always the safest. Add a new numbered changeset that patches the data.

Migration 018 uses option 1: the file was updated for documentation clarity, `<validCheckSum>9:08bb6e86c48052832c073df88ebc3565</validCheckSum>` accepts the already-applied version, and migration 020 handles the actual data patch.

**Never change a checksum in `DATABASECHANGELOG` directly** — that table is Liquibase's source of truth and manual edits corrupt the audit trail.

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
