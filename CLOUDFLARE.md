# CLOUDFLARE.md — Deployment & Worker Configuration

## Architecture

SPA + Java API served via Cloudflare Workers + Tunnel under a sub-path.

```
User → materalabs.us/digitaltwin-app/*
         → Worker: digitaltwin-app
         ├─ /digitaltwin-app/api/* → strip prefix → proxy to tunnel
         │       → digitaltwinapp-api.materalabs.us → cloudflared → localhost:8081 → Java API
         └─ /digitaltwin-app/*    → strip prefix → static assets (dist/) + SPA fallback
```

## Account

| Resource | Account | Account ID |
|---|---|---|
| DNS zone `materalabs.us` | Tic.cloud@matera.com | `45281eba1857e04d45fe46d31bdc2f0b` |
| Worker `digitaltwin-app` | Tic.cloud@matera.com | `45281eba1857e04d45fe46d31bdc2f0b` |
| Route `materalabs.us/digitaltwin-app/*` | Tic.cloud@matera.com | `45281eba1857e04d45fe46d31bdc2f0b` |

| Tunnel `digitaltwinapp-api` | Tic.cloud@matera.com | UUID: `dcb4ed6a-a0c0-451a-9e1b-e8c2803f81de` |
| DNS `digitaltwinapp-api.materalabs.us` | CNAME → tunnel | stable hostname for `API_ORIGIN` secret |

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

## Tunnel

```bash
./tunnel-deploy.sh    # starts cloudflared → localhost:8081 (requires .tunnel-token)
```

First-time setup:
```bash
echo "YOUR_TOKEN" > .tunnel-token   # token from Cloudflare dashboard — gitignored
```

Java API must be running on port 8081 before starting the tunnel.

## Files

| File | Purpose |
|---|---|
| `wrangler.jsonc` | Worker name, account ID, routes, ASSETS binding, BASE_PATH var |
| `worker.ts` | API proxy → strips prefix → serves assets → SPA fallback |
| `vite.config.ts` | `base: '/digitaltwin-app/'` — all built asset paths prefixed correctly |
| `tunnel-deploy.sh` | Starts cloudflared tunnel (`--token + --url http://localhost:8081`) |
| `.tunnel-token` | Tunnel token (gitignored — never commit) |
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

### 5. CORS "Invalid CORS request" — Spring rejecting the preflight

The browser sends an `OPTIONS` preflight before a cross-origin `POST`. If Spring rejects it, the browser never sends the real request. The symptom in the Worker debug output is `{"raw":"Invalid CORS request","_status":403}` (JSON, not HTML). Diagnose with:

```bash
curl -X OPTIONS https://your-domain.com/api/auth/google \
  -H "Origin: https://your-domain.com" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: content-type"
```

Expected: `200` with `Access-Control-Allow-Origin` header. If you get 403, the origin is not in the allowed list.

### 6. Spring `application-local.yml` requires explicit profile activation

`application-local.yml` is a Spring profile file. It only loads when the app starts with `--spring.profiles.active=local`. Without the flag, only `application.yml` is read — meaning CORS allowed-origins defaults to `http://localhost:3000` only, and production requests are silently rejected.

**Fix**: Put non-secret production origins (like `https://materalabs.us`) directly in `application.yml`. Reserve `application-local.yml` for secrets (DB credentials) and local overrides. Start the API with:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 7. Debug Worker pattern to identify 403 source

When a 403 appears and it's unclear whether it comes from Cloudflare WAF, the Worker, or the backend, add debug output to the Worker proxy:

```typescript
if (!proxyResponse.ok) {
  const body = await proxyResponse.text();
  let parsed: any = {};
  try { parsed = JSON.parse(body); } catch { parsed = { raw: body.slice(0, 300) }; }
  return Response.json({ ...parsed, _status: proxyResponse.status }, { status: proxyResponse.status });
}
```

- If `raw` contains HTML → Cloudflare WAF or Spring Whitelabel (backend reached)
- If `error` is a JSON string → backend returned structured JSON error
- `"Invalid CORS request"` as raw → Spring rejected the CORS preflight

### 8. Account alignment
Worker and DNS zone must be on the same account. Login as `carlos.netto@matera.com` gives access to both the personal (`Carlos.netto@matera.com's Account`) and business (`Tic.cloud@matera.com's Account`) accounts. The `account_id` in `wrangler.jsonc` explicitly targets the business account (`45281eba…`).
