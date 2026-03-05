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

### QR Scanner — simulated viewfinder
`Activity` tab in the bottom nav replaced with a central raised QR scan button (glowing green circle, `QrCode` icon). Tapping opens `QRScannerModal` — a full-screen overlay with:
- Animated scan line (CSS `@keyframes qr-scan`, sweeping top→bottom in 2.4s loop)
- Corner bracket viewfinder in brand green
- Generic copy: "Scan a Payment QR Code" / "Align any QR code within the frame — we'll detect the type and take you straight to payment"
- No real camera yet (requires HTTPS + permissions wiring)

**Next step:** Wire `jsqr` or `@zxing/library` into `QRScannerModal` to decode actual QR data from the camera feed, then route based on detected format (Pix, X9.150, EVM address, Solana address, etc.).
