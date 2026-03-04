# Digital Twin App

A multicurrency mobile wallet prototype built on Matera's Digital Twin ledger. Supports fiat (USD, BRL) and crypto (USDC, USDT) wallets with real transaction type codes, QR payment scanning, and a fully installable PWA experience.

**Live:** https://materalabs.us/digitaltwin-app

---

## Run Locally

**Prerequisites:** Node.js 18+

```bash
npm install
npm run dev        # http://localhost:3000
```

## Build & Deploy

```bash
npm run build      # outputs to dist/
npm run deploy     # build + wrangler deploy to Cloudflare
```

Deployed to `materalabs.us/digitaltwin-app` via Cloudflare Workers (route-based, no custom domain needed).
See `CLOUDFLARE.md` for full deployment and account details.

## Tech Stack

| Layer | Technology |
|---|---|
| UI | React 19 + TypeScript |
| Styling | Tailwind CSS v4 |
| Build | Vite 6 |
| Deployment | Cloudflare Workers + Static Assets |
| PWA | Web App Manifest + Service Worker |

## Project Structure

```
src/
  App.tsx           # All UI components + routing + nav
  store.tsx         # React Context state: wallets, transactions, actions
  types.ts          # TypeScript types + TX transaction code constants
  main.tsx          # React DOM entry point
  index.css         # Tailwind theme + custom animations
public/
  manifest.json     # PWA manifest
  sw.js             # Service worker (stale-while-revalidate)
  icon.svg          # App icon (DT mark, brand colors)
worker.ts           # Cloudflare Worker: strips /digitaltwin-app prefix, serves assets, SPA fallback
wrangler.jsonc      # Cloudflare Worker config
```

## Installing as a Mobile App (PWA)

- **iOS Safari:** Share → Add to Home Screen
- **Android Chrome:** Menu → Install App (or banner prompt)
- **Desktop Chrome:** Install icon in address bar

See `CLAUDE.md` for architecture notes and `HISTORY.md` for decisions log.
