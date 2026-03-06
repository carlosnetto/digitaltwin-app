# HISTORY.md — Decisions & Changes Log

Chronological record of significant decisions, changes, and lessons learned.

---

## Mar 2026 — Initial Build Session

### Origin
Project scaffolded from Google AI Studio template (`ybank.me` wallet concept). Originally named `matera-wallet`, renamed to `digitaltwin-app` to reflect the Matera Digital Twin ledger branding.

### Renamed from `matera-wallet` to `digitaltwin-app`
All app-name references updated across: `package.json`, `index.html`, `public/manifest.json`, `public/sw.js`, `public/icon.svg`, `metadata.json`. CSS color variables (`--color-matera-*`) retained — they are the design system palette, not the app name.

### Removed Gemini API dependency
The Google AI Studio template bundled `@google/genai` and injected `GEMINI_API_KEY` at build time via `vite.config.ts`. This was removed entirely (package uninstalled, `define` block removed, `.env.example` cleaned up) since the app does not use the Gemini API.

**Lesson:** The AI Studio template's `vite.config.ts` embeds the API key into the JS bundle at build time — visible to any user inspecting the network. Never use this pattern for production secrets.

### PWA — Installable on mobile and desktop
Added:
- `public/manifest.json` — name, short_name, icons, display: standalone, theme/background colors
- `public/sw.js` — service worker with stale-while-revalidate for assets, network-first for navigation, SPA fallback
- `public/icon.svg` — "DT" mark on brand blue (#001E60) background, brand green (#00E5FF) text
- `index.html` — manifest link, theme-color, iOS PWA meta tags, SW registration

**Lesson:** Vite does not rewrite inline `<script>` content. The service worker registration path (`/sw.js`) must use `import.meta.env.BASE_URL` to resolve correctly under a sub-path. Same applies to `src` attributes in JSX — use `` `${import.meta.env.BASE_URL}assets/...` `` for public folder references.

### Deployed to Cloudflare Workers at `materalabs.us/digitaltwin-app`
Route-based deployment (not custom domain) to coexist with the existing `materalabs` worker at the root. Required:
- `vite.config.ts`: `base: '/digitaltwin-app/'` so asset paths in `dist/index.html` are prefixed correctly
- `worker.ts`: strips `/digitaltwin-app` prefix before serving from `dist/`, SPA fallback for 404s
- `wrangler.jsonc`: two route entries — `materalabs.us/digitaltwin-app/*` and `materalabs.us/digitaltwin-app` (exact match needed for no-trailing-slash case)

**Lesson:** Vite's `base` option rewrites asset paths in built HTML but does NOT rewrite inline script content. Public-folder asset references in JSX (`src="/assets/..."`) break under a sub-path — must use `import.meta.env.BASE_URL`.

### `.DS_Store` cleanup
`.gitignore` had `.DS_Store` without `**/` prefix, so it only matched the root. Changed to `**/.DS_Store`. All existing `.DS_Store` files in root, `dist/`, and `public/` deleted and redeployed to remove from Cloudflare edge.

### Real transaction codes from mini-core
Transaction type names replaced ("Sent" / "Received") with real codes and labels sourced from `/Users/cnetto/Git/mini-core/db/changelog/changes/005-seed-data.xml`.

Selected subset (21 codes from 86 total):
- Credits (10xxx): Direct Deposit - Payroll, ACH Credit, Zelle Received, RTP Received, FedNow Received, External Transfer In, Merchant Credit / Refund, Reward / Cashback, P2P Received
- Debits (20xxx): Debit Card Purchase, Debit Card Purchase - Online, Debit Card Purchase - Recurring, ATM Withdrawal, Zelle Sent, Bill Payment, Bill Payment - Recurring, P2P Sent, Loan Payment, Credit Card Payment
- Fees (30xxx): Monthly Maintenance Fee, ATM Fee - Non-Network

`Transaction` type extended with `transactionCode: number` and `description: string`. `TX` constants map added to `types.ts`. Seed data expanded from 8 to 30 transactions across all 4 wallets.

### Spring Boot Backoffice — Initial scaffold
Added `backoffice/` as a separate Maven module (Spring Boot 3.3 / Java 21). Not part of the Vite/Cloudflare frontend — runs as a separate process on port 8080.

Core adaptor layer:
- `SolanaAdaptorImpl` — random keypair generation, SLIP-0010 HD derivation, SOL/SPL balances, send SOL/token, transaction history
- `CircleMintAdaptorImpl` — gated behind `circle.enabled=false`; compiles but does nothing until API key is configured
- Interface hierarchy: `BlockchainAdaptor → SolanaAdaptor → SolanaAdaptorImpl`

SDK choice: `software.sava` (Solana Foundation endorsed). NOT `solanaj` (unmaintained).

BIP39 mnemonics via `bitcoinj-core:0.17` (`MnemonicCode.toSeed()` — static method).

**Lesson:** `MnemonicCode.toSeed()` is a static method despite looking like an instance call. IDE warns when called as `MnemonicCode.INSTANCE.toSeed()` — must use `MnemonicCode.toSeed(words, "")`.

---

### Mar 2026 — Backoffice: Database + Provisioning Layer

#### IDE warnings cleanup
Fixed a batch of warnings in the adaptor layer:
- `CircleMintAdaptorImpl`: removed `"rawtypes"` from `@SuppressWarnings` (only `"unchecked"` needed); fixed BigDecimal → long conversion with `× 1_000_000L`
- `SolanaAdaptor`: removed unused `import java.math.BigDecimal`
- `SolanaAdaptorImpl`: removed unused `BigDecimal` import and `LAMPORTS_PER_SOL` constant; added `@SuppressWarnings("unused")` on `nativeProgramClient` (kept for future ATA creation); fixed `MnemonicCode.toSeed()` static call

Added `maven-pmd-plugin:3.23.0` for programmatic static analysis (`mvn pmd:pmd`).
SpotBugs was tried but removed — incompatible with Java 25 runtime (class file major version 69).

#### PostgreSQL connectivity
Connected to existing Docker container `global_banking_db` (postgres:16-alpine).
Credentials sourced from `~/Git/mini-core/.env`:
- `jdbc:postgresql://localhost:5432/banking_system`, user=`admin`, password=`mysecretpassword`

Stored in `src/main/resources/application-local.yml` (gitignored). Added to `.gitignore`.

#### Liquibase migrations (9 changesets)
Created `blockchain_schema` with tables:
- `blockchains`, `seed_groups` (with `active` + `next_derivation_index`), `wallets`
- `wallet_creation_requests` (idempotency)
- `tokens` (seeded USDC + USDT on Solana)
- `transactions` (outbound lifecycle: PENDING → SUBMITTED → CONFIRMED/FAILED)
- `received_transactions` (inbound credits; UNIQUE tx_hash)
- `monitoring_cursors` (per-blockchain polling cursor)

**Lesson:** PL/pgSQL `$` dollar-quoting blocks must use `splitStatements:false` in the Liquibase changeset pragma. Otherwise Liquibase splits the function body on semicolons and fails with a parse error.

#### Provisioning layer (managed wallets)
New services and repositories on top of the Solana adaptor:

- `SeedGroupRegistry` — `ConcurrentHashMap<UUID, String>` mnemonics, RAM only, never on disk
- `SeedLoader` (CommandLineRunner) — prompts operator for mnemonic at startup, validates with `MnemonicCode.INSTANCE.check()`
- `WalletProvisioningService` — `@Transactional` HD derive + DB persist, idempotent per `requestId`
- `TransactionProvisioningService` — `@Transactional` token send with full PENDING → SUBMITTED lifecycle
- `MonitoringPersistenceService` — `@Transactional` atomic: `INSERT received_transactions` + `UPDATE monitoring_cursors`
- `WalletMonitoringService` — `@Scheduled(fixedDelayString = "30000")` per-address × per-token polling
- `WalletController` — `POST /api/wallets` + `POST /api/wallets/send`

Key design decisions:
- **Atomic index claim:** `UPDATE seed_groups SET next_derivation_index = next_derivation_index + 1 RETURNING ... - 1 AS claimed_index` — no SELECT MAX() race condition
- **Idempotency at DB level:** UNIQUE constraints on `(request_id, blockchain_id)` for wallets and `(request_id)` for transactions; `ON CONFLICT DO NOTHING` for received transactions
- **No HTTP between Java classes:** `WalletProvisioningService` calls `SolanaAdaptorImpl` directly — same JVM, no REST round-trip
- **UUIDv7** via `com.github.f4b6a3:uuid-creator:6.0.0` for monotonic, time-sortable IDs on transaction tables

Added `spring-boot-starter-jdbc`, `postgresql` (runtime), `liquibase-core`, `uuid-creator:6.0.0` to `pom.xml`. Added `@EnableScheduling` to `BackofficeApplication`.

---

---

### Mar 2026 — digitaltwinapp-api: Google Auth + Cloudflare Tunnel

#### New `api/` module (Spring Boot — port 8081)
Added a separate Spring Boot module (`api/`) to handle authentication. Intentionally named `digitaltwinapp-api` (not `digitaltwin-api`) to signal it serves the app, not the Digital Twin ledger itself.

Key components:
- `AuthController` — `POST /api/auth/google`, `GET /api/auth/me`, `POST /api/auth/logout`
- `GoogleAuthService` — validates Google access token via userinfo endpoint, checks `@matera.com` domain, queries `digitaltwinapp.users`
- `WebConfig` — CORS allowed-origins from `app.allowed-origins` property
- Spring Session JDBC — session stored in PostgreSQL (`SPRING_SESSION` table in `public` schema)

#### Liquibase schema (`digitaltwinapp`)
3 migrations in `api/src/main/resources/db/changelog/`:
- `001` — `CREATE SCHEMA IF NOT EXISTS digitaltwinapp`
- `002` — `digitaltwinapp.users` table: `id UUID`, `email VARCHAR UNIQUE`, `name VARCHAR`, `status VARCHAR(20)` with check `IN ('active', 'suspended')`
- `003` — seed row: `carlos.netto@matera.com / Carlos Netto / active`

**Lesson (Liquibase chicken-and-egg):** Never set `spring.liquibase.default-schema` to a schema that doesn't exist yet. Liquibase needs to write its own tracking tables before the migration that creates the schema runs. Solution: leave `default-schema` unset — Liquibase tracks in `public`, migration 001 creates `digitaltwinapp`.

#### Cloudflare Tunnel (`digitaltwinapp-api`)
Named tunnel exposing `localhost:8081` at `digitaltwinapp-api.materalabs.us`. Uses `--token + --url` pattern — no ingress config files needed.

- Tunnel UUID: `dcb4ed6a-a0c0-451a-9e1b-e8c2803f81de`
- DNS CNAME already routed: `digitaltwinapp-api.materalabs.us → <UUID>.cfargotunnel.com`
- `tunnel-deploy.sh` — reads `.tunnel-token` (gitignored), starts cloudflared

Worker `API_ORIGIN` secret: `https://digitaltwinapp-api.materalabs.us`

#### Worker API proxy
`worker.ts` updated to proxy `/digitaltwin-app/api/*` to the tunnel. Critical: construct outbound fetch explicitly — do NOT copy the entire Request object (copies `credentials: 'include'` which throws in the Worker runtime).

Added debug pattern: capture non-OK response body as `{ raw, _status }` to identify whether 403 comes from Cloudflare WAF (HTML) or the backend (JSON).

#### Frontend auth integration
- All API fetches use `` `${import.meta.env.BASE_URL}api/auth/...` `` — scoped under `/digitaltwin-app/` to avoid conflicts with other apps on `materalabs.us`
- `useGoogleLogin` with `scope: 'openid email profile'` for the implicit flow
- Error handling: `try { data = await res.json() } catch {}` then `data.error ?? \`Server error (${res.status})\``
- Vite proxy: `/digitaltwin-app/api` → `http://localhost:8081` (strips prefix)

**Lesson (CORS + Spring profiles):** `application-local.yml` only loads when `--spring.profiles.active=local` is active. Without the flag, `app.allowed-origins` defaults to `http://localhost:3000` only, causing Spring to return `403 Invalid CORS request` for all production traffic. Fix: put non-secret production origins directly in `application.yml`.

**Lesson (debug workflow):** When a mysterious 403 appears, test the CORS preflight directly with curl before debugging in the browser: `curl -X OPTIONS ... -H "Origin: ..." -H "Access-Control-Request-Method: POST"`. A 403 with `{"raw":"Invalid CORS request"}` immediately identifies Spring CORS rejection vs. Cloudflare WAF.

### QR Scanner — simulated viewfinder
`Activity` tab in the bottom nav replaced with a central raised QR scan button (glowing green circle, `QrCode` icon). Tapping opens `QRScannerModal` — a full-screen overlay with:
- Animated scan line (CSS `@keyframes qr-scan`, sweeping top→bottom in 2.4s loop)
- Corner bracket viewfinder in brand green
- Generic copy: "Scan a Payment QR Code" / "Align any QR code within the frame — we'll detect the type and take you straight to payment"
- No real camera yet (requires HTTPS + permissions wiring)

**Next step:** Wire `jsqr` or `@zxing/library` into `QRScannerModal` to decode actual QR data from the camera feed, then route based on detected format (Pix, X9.150, EVM address, Solana address, etc.).

---

## Mar 2026 — Live Wallets, Exchange Rates & Buy/Sell Conversions

### CORS fix — production origin must be in `application.yml`
`app.allowed-origins` was only set in `application-local.yml`, which only loads with `--spring.profiles.active=local`. In production the property defaulted to `http://localhost:3000`, causing Spring to return `403 Invalid CORS request` for all preflight requests from `https://materalabs.us`.

**Fix:** Added `https://materalabs.us` directly to `application.yml`. Non-secret origins always go in the base config.

**Lesson:** `application-{profile}.yml` is additive override, not a fallback. If a property must be set in production, it goes in `application.yml`.

### SeedLoader Scanner resource leak fixed
`Scanner` was being constructed inside the retry loop — a new one per attempt, wrapping `System.in`. This triggers a Sonar S2093 warning because closing the Scanner would also close `System.in`, breaking all subsequent reads. Fixed by making `Scanner` a field initialized once, with `@SuppressWarnings("java:S2093")` explaining the intentional non-close.

### Auto-provisioning new @matera.com users
`GoogleAuthService` now auto-inserts unknown `@matera.com` users on first successful Google login. If the email is not in `digitaltwinapp.users`, the user is inserted with `status=active` and `name` from the Google profile. Subsequent logins hit the existing row normally.

### Sequential `user_id` (migration 004)
Added `user_id BIGINT` column to `digitaltwinapp.users` (sequence starts at 1003; existing seeded users assigned 1000, 1001, 1002). This sequential ID drives the mini-core account number formula (`user_id * 1000 + currency_id`) and is used as FK in `user_accounts` and `conversions`.

### Currencies table (migration 005)
New `digitaltwinapp.currencies` table with IDs starting at 100 to avoid collision with mini-core:

| id  | code | name             | is_fiat |
|-----|------|------------------|---------|
| 100 | USD  | US Dollar        | true    |
| 101 | BRL  | Brazilian Real   | true    |
| 103 | USDC | USD Coin         | false   |
| 104 | USDT | Tether           | false   |

EUR (102) was briefly added but removed — mini-core didn't have it seeded and it's not needed.

Migration 008 added `is_fiat BOOLEAN` and `logo_url VARCHAR(200)`. Crypto wallets carry relative asset paths (`assets/Circle_USDC_Logo.svg.png`, `assets/tether-usdt-logo.svg`) that the frontend resolves with `import.meta.env.BASE_URL`.

### User accounts table (migration 006)
`digitaltwinapp.user_accounts` correlates users ↔ currencies ↔ mini-core account IDs.
- Account number formula: `user_id * 1000 + currency_id`
- `minicore_account_id` is populated after mini-core confirms creation (≥ 0)
- `ON CONFLICT (user_id, currency_id) DO NOTHING` for idempotency

### pg_notify provisioning trigger (migration 007)
PostgreSQL trigger on `digitaltwinapp.users INSERT` fires `pg_notify('user_created', user_id::text)`. The Java `PostgresNotificationListener` daemon picks this up and calls `UserAccountProvisioningService.provisionUser(userId)` in real time, creating one mini-core account per currency. A `@Scheduled(fixedDelayString="60000")` catch-all retries any rows still missing their accounts after 60 seconds.

**Note:** `postgresql` dependency in `pom.xml` was `<scope>runtime</scope>`, which hid `PGConnection`/`PGNotification` from the compiler. Fixed by removing the scope.

### Live wallet balances from mini-core (GET /api/wallets)
`WalletService` queries `user_accounts JOIN currencies`, then fetches each account's `available_balance` from `GET /api/accounts/{id}` in mini-core. Returns a `List<WalletDto>` with live balances.

`store.tsx` was completely rewritten — hardcoded wallet data removed, replaced by `fetch(BASE_URL + 'api/wallets')` on mount and via `refreshWallets()`. `App.tsx` guards against empty wallet list during load with a "Loading wallets…" placeholder to prevent a `TypeError` on `activeWallet.currency`.

### Liquidity Buffer (migration 009)
System account seeded: `user_id=1`, `email=liquidity-buffer@system`, `name=Liquidity Buffer`, `status=active`. Cannot log in via Google (email domain check rejects it). Has one mini-core account per currency, provisioned by the same daemon. Serves as the counterparty for all buy/sell conversions.

### Exchange rates table (migration 010)
`digitaltwinapp.exchange_rates` stores all 12 directional pairs for 4 currencies (4 × 3). Stablecoin pairs (USDC↔USD, USDT↔USD, USDC↔USDT) are locked at `rate=1.0` with `is_stablecoin_pair=true` and never updated.

`ExchangeRateService` runs `@Scheduled(fixedDelayString="600000", initialDelayString="10000")` and refreshes all non-stablecoin pairs from `open.er-api.com/v6/latest/{CODE}` (free, no API key). USDC and USDT are mapped to USD for the external lookup.

### Buy/Sell/Convert conversions (migration 011 + ConversionService)
`digitaltwinapp.conversions` table records every completed exchange:
- Sequential `id` (BIGINT sequence, not UUID)
- `user_id`, `from_currency_id`, `to_currency_id`, `from_amount`, `to_amount`, `rate`
- `user_debit_tx_id`, `user_credit_tx_id`, `pool_credit_tx_id`, `pool_debit_tx_id` — all four mini-core transaction IDs

`POST /api/wallets/convert` accepts `{ fromCurrencyCode, toCurrencyCode, fromAmount }`. `ConversionService` detects the operation type from `is_fiat` flags on both currencies and selects the appropriate transaction codes:

| Operation | User debit | User credit | Pool credit | Pool debit |
|-----------|-----------|------------|-------------|------------|
| Buy (fiat→crypto) | 50005 | 40003 | 10018 | 20021 |
| Sell (crypto→fiat) | 50003 | 40005 | 10018 | 20021 |
| Convert (crypto→crypto) | 50002 | 40002 | 10018 | 20021 |
| Convert (fiat→fiat) | 50006 | 40006 | 10018 | 20021 |

Amounts are truncated (not rounded) to each currency's `decimal_places` before being sent to mini-core. Mini-core enforces 6dp for USDC/USDT and 2dp for USD/BRL — exceeding this returns 422.

**UI menu per wallet type:**
- Crypto wallets: Receive, Send, Buy, Sell, Convert (crypto→crypto only)
- Fiat wallets: Receive, Send, Convert (fiat→fiat only)

All modals fetch the live exchange rate from `GET /api/wallets/rate?from=X&to=Y` on open. The Confirm button stays disabled until the rate is loaded.

`GET /api/wallets/rate` — session-protected endpoint returning `{ rate: <BigDecimal> }` for any currency pair in `exchange_rates`.

**Lesson:** `Number(d.rate)` returns `NaN` when the fetch fails with a non-OK response (the error JSON has no `rate` field). Always check `r.ok` before parsing, and guard `isNaN()` before setting state.

**Lesson:** Always truncate amounts to the target currency's decimal precision before calling mini-core. Use `RoundingMode.DOWN` — never round up a financial amount the user didn't agree to pay.

---

## Mar 2026 — Transactions, P2P Transfers & UX Polish

### Live transaction history
`GET /api/wallets/transactions?currencyCode=X` fetches up to 50 transactions from mini-core for the active account. Sorted by `transaction_id DESC` (explicit sort — not relying on insertion order). Descriptions are sentence-cased server-side before returning (`"DIRECT DEPOSIT - PAYROLL"` → `"Direct deposit - payroll"`). Capped at 50 with a footer notice when the limit is hit.

**Lesson:** `Collections.reverse()` on the list returned by mini-core is fragile — depends on mini-core's undefined sort order. Sort explicitly on `transaction_id DESC` instead.

### Currency-aware decimal display
`WalletDto` now carries `decimal_places` from `digitaltwinapp.currencies`. Propagated through `store.tsx` → `Wallet` type and used for both balance display and transaction amount formatting. Values: 2 for USD/BRL, 6 for USDC/USDT.

Previously both were hardcoded to 2 decimal places, causing USDC/USDT transactions to show e.g. `5.00` instead of `5.000000`.

### Wallet metadata cache (localStorage)
After the first successful `/api/wallets` response, the full wallet list (including `decimalPlaces`, currency names, account IDs) is stored in `localStorage` under `dt_wallets`. On subsequent page loads, cached data is displayed immediately; the API is still called to refresh live balances. Cache is cleared on logout.

This means `decimal_places` and other metadata never need to be re-queried on page refresh — the DB hit only occurs when the balance needs updating.

### Welcome credit for new users
`UserAccountProvisioningService.provision()` now posts a `CREDIT` of 10,000 BRL with transaction code `10001` (Cash Deposit) immediately after the BRL mini-core account is created. New users land with funds already available to explore all features.

### Refresh button on balance card
Small `RefreshCw` icon added inline next to "Available Balance". Tapping it concurrently refreshes wallet balances (via `refreshWallets()`) and reloads the transaction list for the active account. Spins while loading; disabled to prevent double-trigger.

### Google profile picture in header
The hardcoded "CN" initials in the mobile header were replaced with the user's Google profile photo. Tapping it opens a dropdown showing the full name and a Sign out button (with `LogOut` icon). Desktop sidebar was unchanged — it already showed the profile.

### Bottom nav and Settings
- "Wallets" label renamed to "Accounts" (bottom nav and desktop sidebar)
- Settings button opens a modal with:
  - **Language:** English (static — no multi-language planned)
  - **Timezone:** grouped dropdown for Brazil (BRT/AMT/ACT/FNT) and US (ET/CT/MT/PT/AKT/HST)
- Selected timezone stored in `localStorage` (`dt_timezone`). Transaction timestamps converted to the selected timezone via `toLocaleString('en-US', { timeZone })`. Defaults to browser-detected timezone.
- Transaction timestamps upgraded from date-only (`effective_date`) to full datetime with seconds (`created_at` field), displayed in the user's selected timezone.

### Action menu — crypto vs. fiat
Crypto wallets (USDC, USDT): **Receive / Send / Buy / Sell** — no Convert.
Fiat wallets (USD, BRL): **Receive / Send / Convert** — no Buy/Sell.

Previously crypto wallets also showed a Convert (crypto↔crypto) button. Removed to simplify the UX — the Buy/Sell path already handles cross-type conversion via the liquidity pool.

### P2P transfers between platform users (migration 012–013)
`POST /api/wallets/p2p` — internal transfer between any two platform users for the same currency:

1. `GET /api/users/lookup?email=X` resolves recipient email → name for confirmation screen
2. Frontend shows 3-step flow: enter amount + email → confirm with name → success
3. Backend validates: not self-transfer, recipient exists and is active, recipient has the currency account, sender has sufficient balance
4. Posts debit on sender (code 20026 P2P Sent) and credit on recipient (code 10027 P2P Received)
5. Inserts into `digitaltwinapp.p2p_transactions`

**`p2p_transactions` table (final schema after migrations 012 + 013):**

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT | Auto-increment sequence |
| `created_at` | TIMESTAMPTZ | UTC, `DEFAULT now()` |
| `amount` | NUMERIC(20,8) | Transfer amount |
| `debit_tx_id` | BIGINT | mini-core tx for the sender debit (20026) |
| `credit_tx_id` | BIGINT | mini-core tx for the recipient credit (10027) |

Migration 013 dropped `sender_user_id`, `recipient_user_id`, and `currency_id` — all redundant because each mini-core `transaction_id` already links to `account_id` → `user_accounts` → user and currency.

**Lesson:** Don't denormalize when a FK chain already covers the relationship. The `debit_tx_id` → mini-core transaction → account → `user_accounts` → user is a complete, joinable path. Storing user IDs again just creates sync risk.

---

## Mar 2026 — Financial Invariants: Balance Checks & BigDecimal Precision

### Negative balance bug (Sarah's -$98.10)
A user bought $100 of USDC while her USD account held only $1.90, resulting in a -$98.10 balance. Root cause: `ConversionService.convert()` executed all 4 mini-core transactions without first checking whether the sender had sufficient funds. Mini-core has no negative-balance guard at the DB level — it applies debits unconditionally.

**Fix:** `ConversionService` now calls `MiniCoreClient.getAccount(userFromAccount)` after resolving accounts and before posting any transaction. If `available_balance < fromAmountBD`, it throws `IllegalArgumentException("Insufficient <currency> balance: available X, requested Y")` → 400 to the frontend. This mirrors the pattern already in `WalletService.p2pTransfer()`.

**Lesson:** Never assume the downstream system enforces balance rules. Mini-core is a demo ledger — it will happily overdraft. The API layer must validate before mutating.

### Never use `float` or `double` for monetary amounts
`MiniCoreClient.createTransaction()` originally accepted `double amount`. This caused two categories of bugs:

1. **Wrong variable passed** — `ConversionService` was passing the raw `double fromAmount` input parameter to all 4 transactions instead of the truncated `BigDecimal`. The `fromAmountBD` / `toAmountBD` locals existed but weren't always used.

2. **Floating-point serialization** — Even when a truncated `BigDecimal` like `5253.83` was correctly computed, converting it to `double` via `.doubleValue()` and placing it in a `HashMap<String, Object>` caused Jackson to serialize the binary float representation rather than the decimal value. This produced amounts like `5253.83924303` on the wire — 8 decimal places sent to a 2dp BRL account — triggering mini-core 422 errors.

**Fix:** `MiniCoreClient.createTransaction()` signature changed from `double` to `BigDecimal`. All call sites updated to pass truncated `BigDecimal` values directly. Jackson serializes `BigDecimal` at its declared scale, so `5253.83` (scale 2) serializes as `5253.83` exactly.

**Rule:** `double` / `float` must never appear anywhere in a monetary computation or data path. Use `BigDecimal` from input to wire. Truncate with `RoundingMode.DOWN` to the currency's `decimal_places`. The same rule applies to the welcome credit (`new BigDecimal("10000.00")`, not `10_000.0`) and P2P amounts.

### Desktop sidebar Activity button removed
The "Activity" button in the desktop sidebar nav had no `onClick` handler — it was a visual placeholder that did nothing. Removed to avoid confusing users.

---

## Mar 2026 — Decimal Precision: Frontend Inputs & API Truncation

### Every currency/asset has its own decimal places — no assumptions
The system treats every currency and asset uniformly: each has a `decimal_places` value stored in `digitaltwinapp.currencies` and cached in the frontend wallet list. There is no hardcoded assumption that fiat = 2 decimals or crypto = 6 decimals. Examples of why assumptions break:

- USD: 2 — BRL: 2 — USDC: 6 — USDT: 6
- JPY: 0 — KWD: 3 — ETH could be 18

The fiat/crypto distinction in the UI is purely cultural — it drives which actions are shown (Buy/Sell for crypto, Convert for fiat). It has nothing to do with decimal precision.

### Frontend: amount inputs enforce the correct decimal limit per currency
All amount input fields across ConvertModal, BuyModal, SellModal, and SendModal now enforce the currency's `decimal_places` on every keystroke, using two mechanisms:

1. **`sanitizeAmount(val, decimalPlaces)`** — strips non-numeric characters, enforces a single decimal point, and truncates the fractional part to `decimalPlaces` characters. Minus sign is rejected, so negative input is structurally impossible.

2. **`decimalsFor(currency, wallets)`** — looks up `decimal_places` from the cached wallet list by currency code. No hardcoded constants. Called as `decimalsFor(wallet.currency, wallets)` for the primary field and `decimalsFor(fiatCurrency, wallets)` for the paired field — the same pattern regardless of asset type.

3. **`type="text" inputMode="decimal"`** on all inputs — removes browser spinner arrows (which could decrement into negative territory or bypass the decimal limit) while keeping the numeric keyboard on mobile.

Computed (mirror) fields — the amount automatically calculated on the other side of a conversion — also use `toFixed(decimalsFor(currency, wallets))` so they respect the target currency's precision.

### API: truncation is enforced before every mini-core call
`MiniCoreClient.createTransaction()` accepts `BigDecimal` (not `double`). Before every call, amounts are truncated with `RoundingMode.DOWN` to the currency's `decimal_places` from `digitaltwinapp.currencies`:

- `ConversionService`: both `fromAmountBD` and `toAmountBD` are truncated to their respective currency's precision before being posted.
- `WalletService.p2pTransfer`: the input amount is truncated to the sender's currency `decimal_places`.
- `UserAccountProvisioningService`: welcome credit uses `new BigDecimal("10000.00")`.

Mini-core enforces its own decimal limit and returns 422 on violation — the API-side truncation ensures we never reach that error in normal operation.

**Rule:** decimal places are always read from `digitaltwinapp.currencies.decimal_places` (DB) or the frontend wallet cache — never hardcoded by asset class. Both layers (frontend input and API) enforce the limit independently.

---

## Mar 2026 — Rate Fetch Failures & ConvertModal Race Condition

### Bug: "Loading rate…" forever with empty currency dropdown (reported by Dailson)
A user reported the conversion rate staying on "Loading rate…" indefinitely, with the currency dropdown empty. The tunnel logs showed a ~3.5 minute QUIC outage (`10:00–10:04 UTC`). The second attempt worked.

**Two root causes, compounding each other:**

**1. ConvertModal `targetWalletId` race condition.**
`targetWalletId` is a `useState` initialized from `targetOptions[0]?.id || ''`. React's `useState` only evaluates its initial value once at mount. If `wallets` was empty at mount time (no localStorage cache + tunnel was down when the page loaded, so `refreshWallets()` failed), `targetWalletId` was set to `''` and never updated — even after wallets eventually loaded. With `targetWalletId = ''`, `targetWallet` is always `undefined`. The `useEffect` that fetches the rate begins with `if (!targetWallet) return`, so it exits immediately and rate stays `null` forever.

**Fix:** added a `useEffect` that watches `targetOptions.length` and fills in `targetWalletId` whenever it transitions from empty to populated:
```typescript
useEffect(() => {
  if (!targetWalletId && targetOptions.length > 0) setTargetWalletId(targetOptions[0].id);
}, [targetOptions.length, targetWalletId]);
```

**2. Silent `.catch(() => {})` on all three rate fetch useEffects.**
Any network error or non-ok HTTP response was swallowed silently. Rate stayed `null` and "Loading rate…" displayed forever with no user feedback and no way to retry short of closing and reopening the modal. SellModal additionally had no `r.ok` check and no NaN guard — it could silently set rate to `NaN`.

**Fix:** added `rateError` state (set in `.catch`) and a `rateKey` counter. When `rateError` is true, the rate line renders a red **"Could not load rate — tap to retry"** button instead of the infinite spinner. Tapping increments `rateKey`, which is included in the `useEffect` dependency array, re-triggering the fetch without closing the modal. Applied to ConvertModal, BuyModal, and SellModal. SellModal also received the missing `r.ok` check and NaN guard.

**Lesson:** never use `.catch(() => {})` on a fetch that drives UI state. Silent failures leave the user with no indication of what went wrong and no path to recovery. Always set an error state and offer a retry.

### PROTOTYPE watermark on ReceiveModal
The Receive flow generates QR codes and account details that are simulated (not real banking infrastructure). A diagonal red semi-transparent "PROTOTYPE" watermark was added over the modal to make this clear to anyone who sees it. Implemented as an absolutely-positioned `pointer-events-none` overlay so it doesn't block any interactions.

---

## Mar 2026 — SchemaSpy ERD + Cloudflare Sub-path Static Serving

### SchemaSpy ERD for `digitaltwinapp` schema
Added a SchemaSpy ERD for the API database (`digitaltwinapp` schema) mirroring the existing backoffice one. Configuration:

- `api/docker-compose.yml` — runs `schemaspy/schemaspy` with `network_mode: host`; volume mounts to `../public/erd` so generated output lands directly in the frontend's `public/erd/` directory, which Vite copies to `dist/erd/` at build time
- Output gitignored (`public/erd/` added to `.gitignore` alongside `backoffice/docs/erd/`)
- Served publicly at `materalabs.us/digitaltwin-app/erd/`

To regenerate and redeploy:
```bash
cd api
DB_HOST=localhost DB_PORT=5432 DB_NAME=banking_system \
  DB_USERNAME=admin DB_PASSWORD=mysecretpassword DB_SCHEMA=digitaltwinapp \
  docker-compose run schemaspy
cd .. && npm run deploy
```

SchemaSpy generates 100% relative HTML — all asset references (`bower/...`, `tables/...`) are relative paths. The generated output can be relocated to any sub-path without modification.

### Cloudflare ASSETS binding — canonical redirect behavior
Serving the ERD from a sub-path exposed a class of bugs in how Cloudflare's `env.ASSETS` binding handles directory-like paths.

**Behavior discovered:**
- `env.ASSETS.fetch('/erd')` → **307** to `/erd/` (adds trailing slash for directory)
- `env.ASSETS.fetch('/erd/index.html')` → **307** to `/erd/` (pretty-URL: removes `index.html` suffix)
- `env.ASSETS.fetch('/erd/columns.html')` → **307** to `/erd/columns` (removes `.html` extension)
- `env.ASSETS.fetch('/erd/')` → **200** with `dist/erd/index.html` content
- `env.ASSETS.fetch('/')` → **200** with `dist/index.html` content (root index is not redirected)

The redirects are **root-relative** (e.g. `location: /erd/`). When the worker returned them directly to the browser, the browser followed to `materalabs.us/erd/` — losing the `/digitaltwin-app/` base path entirely.

**Two earlier failed approaches:**
1. Try `{path}/index.html` before `{path}` — ASSETS redirects `index.html` too (307), so the redirect still leaked.
2. Try `{path}/index.html` only on 404 — ASSETS returns 307 (not 404) for directory paths, so the 404 branch never fired.

**Correct fix:** intercept any 301/307 from `env.ASSETS.fetch()` with a root-relative `location` header and re-issue the redirect as an absolute URL with the base path prepended:

```typescript
if (assetResponse.status === 301 || assetResponse.status === 307) {
  const location = assetResponse.headers.get('location') ?? '';
  if (location.startsWith('/')) {
    const redirectUrl = new URL(basePath + location, url.origin);
    return Response.redirect(redirectUrl.toString(), assetResponse.status);
  }
}
```

This lets Cloudflare's canonical redirect logic run normally (trailing slash, extension stripping) while keeping the browser under `/digitaltwin-app/`.

**SPA fallback also fixed:** the original fallback used `pathname = '/index.html'`, which ASSETS redirects to `/` — a 307 that leaked to the browser. Changed to `pathname = '/'` directly, which ASSETS serves as 200 without redirect.

**Lesson:** `env.ASSETS.fetch()` is not a transparent file reader — it enforces canonical URL redirects. Never pass `*/index.html` or bare directory paths and expect a 200. Intercept redirects from ASSETS and re-apply your base path before forwarding to the browser.

---

## Mar 2026 — Decoupled Tunnel Startup (Multi-Tunnel Machines)

### Problem: `--url` silently loses to `~/.cloudflared/config.yml`

When migrating the app to a new machine that already had another Cloudflare Tunnel running, `./tunnel-deploy.sh` appeared to start correctly (4 connections registered, correct tunnel UUID in logs) but all requests returned a bare `404` with no body — never reaching the Java API.

Root cause: cloudflared always reads `~/.cloudflared/config.yml` by default, even when `--token` and `--url` are provided on the command line. The other tunnel's `ingress:` block in that file silently overrides `--url`. The tunnel connects to Cloudflare with the correct tunnel ID but routes all traffic using the wrong machine's ingress rules.

Config resolution precedence (highest wins):

| Source | Ingress authority |
|---|---|
| `--config FILE` with `ingress:` block | Wins — no other source consulted |
| `~/.cloudflared/config.yml` with `ingress:` block | Wins over `--url` |
| `--url http://...` flag | Only used when no config file defines ingress |

**Lesson:** `--token` controls which tunnel connects to Cloudflare. It has no effect on ingress routing. `--url` is only a fallback — any `config.yml` on the machine beats it.

### Fix: committed `tunnel-config.yml` per project

`tunnel-config.yml` is committed to the repo containing only the ingress rule. `tunnel-deploy.sh` points `--config` to it, completely bypassing `~/.cloudflared/config.yml`:

```yaml
# tunnel-config.yml — no secrets, safe to commit
ingress:
  - service: http://localhost:8081
```

```bash
CONFIG_FILE="$(dirname "$0")/tunnel-config.yml"
cloudflared tunnel --config "$CONFIG_FILE" run --token "$TOKEN"
```

Key details:
- `--config` is a **tunnel-level** flag — it must come before `run`, not after
- `--url` is omitted — the ingress block in the config makes it redundant
- The config has no secrets — tunnel identity and credentials come from `--token` (read from `.tunnel-token`, gitignored)
- The config travels with the repo — `git pull` on a new machine is sufficient, no manual setup
- The other tunnel on the machine is completely unaffected

The system now starts and routes correctly regardless of what other tunnels or cloudflared configurations exist on the machine.
