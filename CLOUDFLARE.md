# CLOUDFLARE.md — Deployment & Worker Configuration

## Architecture

Static SPA served via Cloudflare Workers under a sub-path. No backend, no tunnel.

```
User → materalabs.us/digitaltwin-app/*
         → Worker: digitaltwin-app
         → strips /digitaltwin-app prefix
         → serves static assets from dist/
         → SPA fallback: 404 → index.html
```

## Account

| Resource | Account | Account ID |
|---|---|---|
| DNS zone `materalabs.us` | Tic.cloud@matera.com | `45281eba1857e04d45fe46d31bdc2f0b` |
| Worker `digitaltwin-app` | Tic.cloud@matera.com | `45281eba1857e04d45fe46d31bdc2f0b` |
| Route `materalabs.us/digitaltwin-app/*` | Tic.cloud@matera.com | `45281eba1857e04d45fe46d31bdc2f0b` |

No tunnel — the app is a pure SPA with no backend to proxy.

### Wrangler Login

Login must be with `carlos.netto@matera.com`, which has access to the `Tic.cloud` business account:

```bash
npx wrangler whoami   # verify: should show Tic.cloud / 45281eba...
npx wrangler login    # re-authenticate if needed
```

## Deploy

```bash
npm run deploy        # runs: npm run build && npx wrangler deploy
```

## Files

| File | Purpose |
|---|---|
| `wrangler.jsonc` | Worker name, account ID, routes, ASSETS binding, BASE_PATH var |
| `worker.ts` | Strips `/digitaltwin-app` prefix → serves assets → SPA fallback |
| `vite.config.ts` | `base: '/digitaltwin-app/'` — all built asset paths prefixed correctly |
| `public/manifest.json` | PWA manifest (copied to `dist/` by Vite) |
| `public/sw.js` | Service worker (copied to `dist/` by Vite) |

## Sub-Path Deployment — Three Things That Must Align

| Layer | Setting | If Wrong |
|---|---|---|
| Vite `base` | `'/digitaltwin-app/'` | Assets 404 (paths resolve to root domain) |
| Worker prefix strip | strips `/digitaltwin-app` before `ASSETS.fetch` | Assets 404 (dist/ has `assets/…`, not `digitaltwin-app/assets/…`) |
| JSX asset references | `${import.meta.env.BASE_URL}assets/…` | Images 404 at runtime |

## Route Entries

Two routes are required in `wrangler.jsonc`:

```jsonc
"routes": [
  { "pattern": "materalabs.us/digitaltwin-app/*", "zone_name": "materalabs.us" },
  { "pattern": "materalabs.us/digitaltwin-app",   "zone_name": "materalabs.us" }
]
```

Without the exact-match entry (no `/*`), requests to `/digitaltwin-app` (no trailing slash) fall through to the existing root `materalabs` worker.

## ASSETS Binding

`wrangler.jsonc` must include `"binding": "ASSETS"` under `assets`:

```jsonc
"assets": {
  "directory": "./dist",
  "binding": "ASSETS"
}
```

Without it, `env.ASSETS` is `undefined` and the worker crashes with **Cloudflare error 1101** at runtime (no build-time warning).

## Worker Logic

`worker.ts` does three things in order:

1. **Strip prefix** — `/digitaltwin-app/assets/…` → `/assets/…`
2. **Serve asset** — `env.ASSETS.fetch(strippedRequest)`
3. **SPA fallback** — if asset returns 404, serve `index.html` (enables client-side routing)

## Coexistence with Existing Worker

The `materalabs.us` root domain is served by a separate `materalabs` worker. Route specificity in Cloudflare ensures `materalabs.us/digitaltwin-app/*` takes priority — the two workers coexist without conflict.

## Cache

After deploying, if stale content is served:
1. Dashboard → materalabs.us → Caching → Purge Everything
2. Hard-refresh browser (Cmd+Shift+R) or test in incognito

## Lessons Learned

### 1. Vite `base` does not rewrite inline script content
`import.meta.env.BASE_URL` works in JSX and module scripts, but NOT in `<script>` tags written as raw HTML in `index.html`. The service worker registration was initially `/sw.js` (absolute), which broke under the sub-path. Fixed by using `` `${import.meta.env.BASE_URL}sw.js` ``.

### 2. JSX asset references from `public/` must use BASE_URL
`src="/assets/..."` resolves to the root domain, not the sub-path. Images for USDC and USDT logos were broken until changed to `` `${import.meta.env.BASE_URL}assets/...` ``.

### 3. `.DS_Store` was uploaded to Cloudflare
macOS `.DS_Store` files were present in `dist/` and got uploaded. Fixed by adding `**/.DS_Store` to `.gitignore` (the plain `.DS_Store` entry only matches root) and redeploying.

### 4. Wrangler warning about asset path matching
Wrangler warns: *"materalabs.us/digitaltwin-app/* will match assets: dist/digitaltwin-app/*"*. This is expected — the warning reflects that Cloudflare's built-in asset serving would look for `dist/digitaltwin-app/…`, but our custom worker strips the prefix and serves from `dist/` directly. The warning can be ignored.

### 5. Account alignment
Worker and DNS zone must be on the same account. Login as `carlos.netto@matera.com` gives access to both the personal (`Carlos.netto@matera.com's Account`) and business (`Tic.cloud@matera.com's Account`) accounts. The `account_id` in `wrangler.jsonc` explicitly targets the business account (`45281eba…`).
