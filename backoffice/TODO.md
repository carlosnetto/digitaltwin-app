# Backoffice TODO

## Solana

- [ ] **Priority fee support** — Add `ComputeBudgetProgram.setComputeUnitPrice()` instruction to `sendNative` and `sendToken`.
  - Query current market rate from Helius `POST /v0/rpc/getPriorityFeeEstimate` (returns low/medium/high tiers)
  - Expose `priorityLevel` param on send endpoints (`"low"`, `"medium"`, `"high"`, `"none"`)
  - Default to `"medium"` for live use; `"none"` for testing
  - Without this, txs may be delayed or dropped during network congestion

- [ ] **Recipient ATA creation** — `sendToken` fails if recipient has never held the token (no ATA exists).
  - Add `createAssociatedTokenAccount` instruction before the transfer when ATA is missing
  - Check existence with `rpcClient.getAccountInfo(recipientAta)` — null means it doesn't exist
  - Costs ~0.002 SOL (rent-exempt minimum), paid by sender

- [ ] **Dynamic fee estimation** — Replace hardcoded `5_000L` in `estimateFee()` with a real RPC call.
  - Use `rpcClient.getFeeForMessage(serializedMessage).join()`

---

## Circle (not yet active — `circle.enabled=false`)

All Circle code compiles but is gated behind `circle.enabled=true` in `application.yml`.
Nothing runs until that flag is set and a valid API key is provided.

### Before enabling

- [ ] **Get Circle API credentials** — Sign up at https://developers.circle.com, create a sandbox account, and obtain an API key.
  - Add to `application-local.yml`: `circle.api-key: <key>` + `circle.enabled: true`

- [ ] **Verify API endpoint paths** — Circle has both v1 and v2 APIs; some operations moved.
  - Current impl uses `/accounts/{id}`, `/transfers` — validate against latest docs at https://developers.circle.com/reference
  - Confirm mint vs transfer distinction (Circle uses "transfers" for on-chain moves)

- [ ] **Amount strategy** — Circle API uses string decimals (e.g. `"1.00"` for 1 USDC), not raw integers.
  - Decide: convert raw units at the adaptor boundary (divide by 10^6 before sending to Circle), or expose separate human-readable amounts for Circle only
  - `CircleOperationResponse.amount` is currently `BigDecimal` — align with raw integer convention or document the exception

- [ ] **Idempotency key handling** — Circle requires a unique `idempotencyKey` per operation to prevent duplicate charges on retry.
  - Currently `UUID.randomUUID()` is used inline — callers cannot retry safely
  - Consider accepting `idempotencyKey` as an optional request field, falling back to a generated one, and storing it so retries reuse the same key

- [ ] **Async operation status** — Circle operations (mint, burn, transfer) are async; they return `pending` immediately.
  - Polling: `GET /api/circle/operation/{operationId}` is implemented — verify it works
  - Webhooks (preferred): register a Circle webhook to push status updates instead of polling

- [ ] **Sandbox vs production** — Circle has separate sandbox (`api-sandbox.circle.com`) and production (`api.circle.com`) base URLs.
  - Add profile-based config: `application-dev.yml` → sandbox URL, `application-prod.yml` → production URL

- [ ] **Test against sandbox** — Once credentials are ready, test the full mint → check status → burn flow end-to-end in Circle sandbox before going to production.
