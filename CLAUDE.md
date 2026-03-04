# CLAUDE.md — Digital Twin App

## Project Context

A multicurrency mobile wallet prototype demonstrating Matera's Digital Twin ledger concept. Supports USD, BRL (fiat) and USDC, USDT (crypto) wallets. Deployed as a PWA at `materalabs.us/digitaltwin-app`.

Originally scaffolded from a Google AI Studio template. Gemini API removed. No backend server.

## Architecture

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
worker.ts       # Cloudflare Worker
wrangler.jsonc  # Cloudflare config
```

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

### No Backend
This is a pure SPA. No server, no tunnel, no API proxy in the worker. The Cloudflare Worker only strips the base path prefix and serves static assets with SPA fallback.

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
