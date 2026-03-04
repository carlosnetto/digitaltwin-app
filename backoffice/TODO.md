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
