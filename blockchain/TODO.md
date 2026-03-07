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

## Provisioning Layer

- [ ] **Transaction confirmation poller** — `transactions` table has SUBMITTED rows that never advance to CONFIRMED.
  - Add a `@Scheduled` job in `TransactionProvisioningService` (or a new `TransactionConfirmationService`)
  - Query `SELECT id, tx_hash FROM transactions WHERE status = 'SUBMITTED'`
  - Call `rpcClient.getTransaction(txHash).join()` — non-null means confirmed on-chain
  - Update status to CONFIRMED; handle FAILED (transactionError present)

- [ ] **Yellowstone gRPC monitoring** — Replace per-address polling with a single gRPC stream.
  - Current polling: O(addresses × tokens) RPC calls per 30s — breaks at >1,000 addresses
  - Target: one `SubscribeRequest` to Helius LaserStream with account filters
  - In-memory `HashSet<String>` of all managed addresses (~320 MB for 10M × 32 B)
  - Recovery: `fromSlot` replay if gap <24h; `getSignaturesForAddress` backfill otherwise
  - See `backoffice/monitoring-architecture.md` for full design

- [ ] **Memo parsing** — `WalletMonitoringService` passes `memo=null` to `persistAndAdvanceCursor`.
  - Parse the `memo` field from Solana transaction log messages
  - Useful for correlating incoming payments to orders (payers can embed an order ID)

- [ ] **SOL native transfers in provisioning layer** — `TransactionProvisioningService.send()` only supports SPL tokens.
  - Add a `sendNative` path for SOL (no token lookup needed, amount in lamports)
  - Or add a `tokenId = "sol"` sentinel that routes to `SolanaAdaptorImpl.sendNative()`

- [ ] **SeedLoader hardening** — Current `SeedLoader` prompts via `Console`/`Scanner` at startup.
  - Production alternative: protected HTTP endpoint (e.g. `POST /admin/seed-groups/{id}/load`)
    accessible only over a localhost-bound interface or VPN
  - Guard with a one-time-use token or mTLS

- [ ] **ATA creation on send** — `sendToken` fails silently if the recipient has no ATA for the token.
  - Check `rpcClient.getAccountInfo(recipientAta)` before sending
  - If null, prepend `createAssociatedTokenAccount` instruction (costs ~0.002 SOL, paid by sender)
  - Already tracked in the Solana section above; repeating here for provisioning layer context

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
