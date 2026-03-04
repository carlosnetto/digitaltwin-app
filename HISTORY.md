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

### QR Scanner — simulated viewfinder
`Activity` tab in the bottom nav replaced with a central raised QR scan button (glowing green circle, `QrCode` icon). Tapping opens `QRScannerModal` — a full-screen overlay with:
- Animated scan line (CSS `@keyframes qr-scan`, sweeping top→bottom in 2.4s loop)
- Corner bracket viewfinder in brand green
- Generic copy: "Scan a Payment QR Code" / "Align any QR code within the frame — we'll detect the type and take you straight to payment"
- No real camera yet (requires HTTPS + permissions wiring)

**Next step:** Wire `jsqr` or `@zxing/library` into `QRScannerModal` to decode actual QR data from the camera feed, then route based on detected format (Pix, X9.150, EVM address, Solana address, etc.).
