# Solana on Java — Backoffice Developer Guide

Complete reference for the Solana integration in `backoffice/`. Covers the SDK,
key derivation, transaction building, REST API, and the large-scale monitoring
architecture for managing millions of wallets.

> **See also:** [`SOLANA-JAVA.md`](./SOLANA-JAVA.md) — verified Sava SDK API reference.
> All code samples in this file use the corrected method names confirmed by runtime
> testing and `javap` decompilation.

---

## Table of Contents

1. [SDK Choice: software.sava (not solanaj)](#1-sdk-choice)
2. [Maven Dependencies](#2-maven-dependencies)
3. [Configuration](#3-configuration)
4. [Spring Beans (SolanaConfig)](#4-spring-beans)
5. [Key Generation & HD Derivation](#5-key-generation--hd-derivation)
6. [Balances](#6-balances)
7. [Sending SOL](#7-sending-sol)
8. [Sending SPL Tokens (USDC / USDT)](#8-sending-spl-tokens)
9. [Transaction History & Confirmation](#9-transaction-history--confirmation)
10. [Associated Token Accounts (ATA)](#10-associated-token-accounts)
11. [REST API Reference](#11-rest-api-reference)
12. [Interface Hierarchy](#12-interface-hierarchy)
13. [Incoming Transaction Monitoring](#13-incoming-transaction-monitoring)
14. [Database Schema (blockchain_schema)](#14-database-schema)
15. [Managed Wallet Provisioning Layer](#15-managed-wallet-provisioning-layer)
16. [Security Principles](#16-security-principles)
17. [Running Locally](#17-running-locally)

---

## 1. SDK Choice

**Use `software.sava` — the official Solana Java SDK. Do NOT use `solanaj`.**

| | `software.sava` | `solanaj` |
|---|---|---|
| Status | Official — Solana Foundation endorsed | Community, unmaintained |
| Latest | `25.x` (2025/2026 active) | `1.28` (stale) |
| API style | Async `CompletableFuture<T>` | Blocking |
| Transaction builder | Typed `Transaction.createTx()` | Manual byte construction |
| Commitment | `Commitment.CONFIRMED` / `FINALIZED` first-class | String-based |

Maven coordinates:

```xml
<dependency>
    <groupId>software.sava</groupId>
    <artifactId>sava-core</artifactId>
    <version>25.3.0</version>
</dependency>
<dependency>
    <groupId>software.sava</groupId>
    <artifactId>sava-rpc</artifactId>
    <version>25.3.0</version>
</dependency>
<dependency>
    <groupId>software.sava</groupId>
    <artifactId>solana-programs</artifactId>
    <version>25.0.0</version>
</dependency>
```

---

## 2. Maven Dependencies

Full dependency block from `pom.xml`:

```xml
<!-- Official Solana Java SDK -->
<dependency>
    <groupId>software.sava</groupId>
    <artifactId>sava-core</artifactId>
    <version>25.3.0</version>
</dependency>
<dependency>
    <groupId>software.sava</groupId>
    <artifactId>sava-rpc</artifactId>
    <version>25.3.0</version>
</dependency>
<!-- SystemProgram, TokenProgram, NativeProgramClient, etc. -->
<dependency>
    <groupId>software.sava</groupId>
    <artifactId>solana-programs</artifactId>
    <version>25.0.0</version>
</dependency>

<!-- BIP39 mnemonic → seed bytes only. Key derivation uses sava. -->
<dependency>
    <groupId>org.bitcoinj</groupId>
    <artifactId>bitcoinj-core</artifactId>
    <version>0.17</version>
</dependency>
```

**Lombok annotation processor** must be declared explicitly in `maven-compiler-plugin`
or Lombok-generated methods (`@Builder`, `@Slf4j`, etc.) will be silently absent:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

---

## 3. Configuration

`src/main/resources/application.yml`:

```yaml
server:
  port: 8080

solana:
  rpc-url: https://api.devnet.solana.com   # mainnet: https://api.mainnet-beta.solana.com
  usdc-mint: EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v   # mainnet
  usdt-mint: Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB   # mainnet
  explorer-base-url: https://solscan.io
```

The `rpc-url` value drives cluster detection throughout `SolanaAdaptorImpl`:
- contains `devnet` → explorer appends `?cluster=devnet`
- contains `testnet` → `?cluster=testnet`
- otherwise → mainnet (no suffix)

**Note:** `SolanaAccounts.MAIN_NET` is used for all clusters because system/token
program addresses are identical on devnet, testnet, and mainnet. Only token *mint*
addresses differ (configured separately above).

---

## 4. Spring Beans

`SolanaConfig.java` registers four beans:

```java
// Shared HTTP/2 client — thread-safe singleton
@Bean
public HttpClient solanaHttpClient() {
    return HttpClient.newHttpClient();
}

// Async JSON-RPC client pointed at configured endpoint
@Bean
public SolanaRpcClient solanaRpcClient(HttpClient solanaHttpClient) {
    return SolanaRpcClient.build()
            .endpoint(URI.create(rpcUrl))
            .httpClient(solanaHttpClient)
            .defaultCommitment(Commitment.CONFIRMED)
            .createClient();
}

// Canonical program addresses (system, token, ATA, etc.)
// MAIN_NET constants work on all clusters
@Bean
public SolanaAccounts solanaAccounts() {
    return SolanaAccounts.MAIN_NET;
}

// Instruction builder for native / SPL programs
@Bean
public NativeProgramClient nativeProgramClient(SolanaAccounts solanaAccounts) {
    return NativeProgramClient.createClient(solanaAccounts);
}
```

All Sava RPC methods return `CompletableFuture<T>`. Call `.join()` to block in a
synchronous Spring MVC context (this is fine — the thread pool absorbs the wait).
For async controllers, chain with `.thenApply()` and return `CompletableFuture<ResponseEntity<T>>`.

---

## 5. Key Generation & HD Derivation

### 5a. Random keypair (single-use wallets)

```java
// Returns 64 bytes: privKey[0..31] || pubKey[32..63]
byte[] keyPairBytes = Signer.generatePrivateKeyPairBytes();
Signer signer       = Signer.createFromKeyPair(keyPairBytes);

String address    = signer.publicKey().toBase58();
// Expose only the 32-byte private key portion (base58-encoded)
String privateKey = Base58.encode(Arrays.copyOfRange(keyPairBytes, 0, 32));
```

### 5b. HD derivation from mnemonic (omnibus / managed wallets)

Derivation standard: **SLIP-0010 ed25519** at path `m/44'/501'/{accountIndex}'/0'`

Step 1 — mnemonic → 64-byte seed (bitcoinj):
```java
List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));
byte[] seed = MnemonicCode.toSeed(words, "");   // static method, no passphrase
```

Step 2 — SLIP-0010 HMAC-SHA512 chain (implemented in `SolanaAdaptorImpl.slip10Derive()`):
```java
// Hardened path segments (bit 31 set)
int[] path = {
    0x8000002C,                      // 44'  (purpose)
    0x800001F5,                      // 501' (Solana coin type)
    0x80000000 + accountIndex,       // n'   (account)
    0x80000000                       // 0'   (change)
};
byte[] privKeyBytes = slip10Derive(seed, path);
Signer signer = Signer.createFromPrivateKey(privKeyBytes);
String address = signer.publicKey().toBase58();
```

REST endpoint: `POST /api/solana/derive` (not yet wired — call `solanaAdaptor.deriveAddress(mnemonic, index)` directly).

**Security:** The seed phrase must never be stored on disk. Load it into RAM only
when deriving keys for a sweep operation, then let the reference go out of scope.
For watch-only monitoring, the server needs zero private keys (addresses are public).

---

## 6. Balances

### Native SOL balance

```java
PublicKey pubKey = PublicKey.fromBase58Encoded(address);
var result = rpcClient.getBalance(pubKey).join();
// result.lamports() → long
BigDecimal sol = BigDecimal.valueOf(result.lamports())
        .divide(BigDecimal.valueOf(1_000_000_000L), 9, RoundingMode.HALF_UP);
```

### SPL token balance (USDC / USDT)

```java
PublicKey owner = PublicKey.fromBase58Encoded(address);
PublicKey mint  = PublicKey.fromBase58Encoded(tokenMintAddress);
PublicKey ata   = findAssociatedTokenAddress(owner, mint);   // see section 10

var tokenAmount = rpcClient.getTokenAccountBalance(ata).join();
// amount() = raw BigInteger, decimals() = scale (6 for USDC/USDT)
BigDecimal balance = new BigDecimal(tokenAmount.amount())
        .movePointLeft(tokenAmount.decimals());
```

**Known mints (mainnet):**

| Token | Mint address |
|---|---|
| USDC | `EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v` |
| USDT | `Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB` |

---

## 7. Sending SOL

```java
Signer signer   = Signer.createFromPrivateKey(Base58.decode(fromPrivateKey));
PublicKey toPub = PublicKey.fromBase58Encoded(toAddress);
long lamports   = amount.multiply(BigDecimal.valueOf(1_000_000_000L)).longValue();

var blockHash = rpcClient.getLatestBlockHash().join();

Instruction ix = SystemProgram.transfer(
        solanaAccounts.invokedSystemProgram(),
        signer.publicKey(),
        toPub,
        lamports);

Transaction tx = Transaction.createTx(
        AccountMeta.createFeePayer(signer.publicKey()),
        List.of(ix));

String signature = rpcClient.sendTransaction(tx, signer, Base58.decode(blockHash.blockHash())).join();
```

**Fee:** Base fee is 5,000 lamports (0.000005 SOL) per signature. For production,
call `rpcClient.getFeeForMessage(serializedMessage).join()` for an exact quote.

---

## 8. Sending SPL Tokens

USDC and USDT both use **6 decimal places** → multiply human amount × 10⁶.

```java
Signer signer          = Signer.createFromPrivateKey(Base58.decode(fromPrivateKey));
PublicKey mintPubKey   = PublicKey.fromBase58Encoded(tokenMint);
PublicKey recipientPub = PublicKey.fromBase58Encoded(toAddress);

PublicKey senderAta    = findAssociatedTokenAddress(signer.publicKey(), mintPubKey);
PublicKey recipientAta = findAssociatedTokenAddress(recipientPub, mintPubKey);

long scaledAmount = amount.multiply(BigDecimal.valueOf(1_000_000L)).longValue();

var blockHash = rpcClient.getLatestBlockHash().join();

Instruction ix = TokenProgram.transfer(
        solanaAccounts.invokedTokenProgram(),
        senderAta,
        recipientAta,
        scaledAmount,
        signer.publicKey());

Transaction tx = Transaction.createTx(
        AccountMeta.createFeePayer(signer.publicKey()),
        List.of(ix));

String signature = rpcClient.sendTransaction(tx, signer, Base58.decode(blockHash.blockHash())).join();
```

> **Prerequisite:** The recipient's ATA must already exist on-chain before the transfer.
> If it doesn't, add a `createAssociatedTokenAccount` instruction before the transfer
> instruction via `nativeProgramClient`. The sender pays the rent (~0.002 SOL).

---

## 9. Transaction History & Confirmation

### History (newest-first signatures)

```java
PublicKey pubKey = PublicKey.fromBase58Encoded(address);
List<?> sigs = rpcClient.getSignaturesForAddress(pubKey, limit).join();

sigs.stream().map(sig -> TransactionResponse.builder()
        .txHash(sig.signature())
        .status(sig.transactionError() == null ? "confirmed" : "failed")
        .slot(sig.slot())
        .timestamp(sig.blockTime().isPresent()
                ? Instant.ofEpochSecond(sig.blockTime().getAsLong()) : null)
        .build());
```

### Confirmation check

```java
var tx = rpcClient.getTransaction(txHash).join();
boolean confirmed = tx != null;
```

### Explorer URL

```java
String cluster = rpcUrl.contains("devnet")  ? "?cluster=devnet"
               : rpcUrl.contains("testnet") ? "?cluster=testnet" : "";
return "https://solscan.io/tx/" + txHash + cluster;
```

---

## 10. Associated Token Accounts

Every SPL token balance lives in an **Associated Token Account (ATA)** — a
program-derived address deterministically computed from `(owner, tokenProgram, mint)`.

```java
private PublicKey findAssociatedTokenAddress(PublicKey owner, PublicKey mint) {
    var tokenProgram = solanaAccounts.tokenProgram();
    var ataProgramId = solanaAccounts.associatedTokenAccountProgram();

    List<byte[]> seeds = List.of(
            owner.toByteArray(),
            tokenProgram.toByteArray(),
            mint.toByteArray());

    return PublicKey.findProgramAddress(seeds, ataProgramId).publicKey();
}
```

This is deterministic and does not require any RPC call. The ATA only needs to
be **created on-chain** before the first deposit.

---

## 11. REST API Reference

Base URL: `http://localhost:8080`

### Managed Wallets (WalletController)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/wallets` | Provision a new HD-derived wallet (idempotent) |
| `POST` | `/api/wallets/send` | Send SPL tokens from a managed wallet (idempotent) |

**`POST /api/wallets`** — Provision a managed wallet

Request:
```json
{ "requestId": "019500ab-...", "blockchainId": "solana" }
```
Response:
```json
{ "requestId": "019500ab-...", "blockchainId": "solana", "publicAddress": "4Nd1m..." }
```

`requestId` is a UUIDv7 from the caller. Replaying the same `requestId` returns the same wallet — no duplicate provisioning.

**`POST /api/wallets/send`** — Send tokens from a managed wallet

Request:
```json
{
  "requestId": "019500ac-...",
  "blockchainId": "solana",
  "fromPublicAddress": "4Nd1m...",
  "toAddress": "7XyZ...",
  "tokenId": "usdc",
  "amount": 1000000
}
```
`amount` is in the token's smallest unit (USDC: 6 decimals → 1 USDC = 1,000,000).

Response:
```json
{
  "requestId": "019500ac-...",
  "blockchainId": "solana",
  "fromPublicAddress": "4Nd1m...",
  "toAddress": "7XyZ...",
  "tokenId": "usdc",
  "amount": 1000000,
  "status": "submitted",
  "txHash": "5Kx...",
  "explorerUrl": "https://solscan.io/tx/5Kx...?cluster=devnet"
}
```

### Raw Solana Adaptor (SolanaController)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/solana/wallet` | Generate new random keypair (private key in response — dev only) |
| `GET` | `/api/solana/wallet/{address}/balance` | Native SOL balance |
| `GET` | `/api/solana/wallet/{address}/token/{mint}/balance` | SPL token balance |
| `GET` | `/api/solana/wallet/{address}/history?limit=20` | Transaction history (max 100) |
| `POST` | `/api/solana/send/sol` | `{ fromPrivateKey, toAddress, amount }` |
| `POST` | `/api/solana/send/token` | `{ fromPrivateKey, toAddress, tokenMint, amount }` |
| `GET` | `/api/solana/tx/{txHash}/status` | `{ txHash, confirmed, explorerUrl }` |

### Example calls

```bash
# Generate a wallet
curl -X POST http://localhost:8080/api/solana/wallet

# SOL balance (devnet)
curl http://localhost:8080/api/solana/wallet/4Nd1m.../balance

# USDC balance
curl http://localhost:8080/api/solana/wallet/4Nd1m.../token/EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v/balance

# Send SOL
curl -X POST http://localhost:8080/api/solana/send/sol \
  -H 'Content-Type: application/json' \
  -d '{"fromPrivateKey":"<base58>","toAddress":"<base58>","amount":0.01}'

# Send USDC
curl -X POST http://localhost:8080/api/solana/send/token \
  -H 'Content-Type: application/json' \
  -d '{
    "fromPrivateKey":"<base58>",
    "toAddress":"<base58>",
    "tokenMint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
    "amount":10.00
  }'
```

### Response shapes

**`WalletInfoResponse`**
```json
{
  "network": "solana",
  "address": "4Nd1m...",
  "publicKey": "4Nd1m...",
  "privateKey": "<base58-32-byte-priv>"
}
```

**`BalanceResponse`**
```json
{ "address": "4Nd1m...", "balance": 1.500000000, "currency": "SOL", "network": "solana" }
```

**`SendResultResponse`**
```json
{
  "txHash": "<signature>",
  "fromAddress": "...",
  "toAddress": "...",
  "amount": 0.01,
  "currency": "SOL",
  "fee": 0.000005000,
  "status": "submitted",
  "explorerUrl": "https://solscan.io/tx/...?cluster=devnet"
}
```

---

## 12. Interface Hierarchy

```
BlockchainAdaptor           ← generic: any chain (Ethereum, Base, …)
└── SolanaAdaptor           ← adds deriveAddress(), estimateFee()
    └── SolanaAdaptorImpl   ← concrete Spring @Service using software.sava

StablecoinIssuerAdaptor     ← generic: Circle, Paxos, …
└── CircleMintAdaptor       ← adds listMints(), getOperationStatus()
    └── CircleMintAdaptorImpl

Provisioning layer (no interface yet — Spring @Services):
  WalletProvisioningService   ← HD derive + DB persist, idempotent via requestId
  TransactionProvisioningService ← send token, full PENDING→SUBMITTED lifecycle
  MonitoringPersistenceService   ← @Transactional: insert received_tx + advance cursor
  WalletMonitoringService        ← @Scheduled polling loop
```

Adding a new chain (e.g. Ethereum): implement `BlockchainAdaptor` + a new `@Service`.
The controller layer depends on `SolanaAdaptor` directly, so a new chain gets its
own controller — no changes to existing code required.

---

## 13. Incoming Transaction Monitoring

### MVP: Per-address polling (implemented)

`WalletMonitoringService` runs on a `@Scheduled` fixed delay (default 30 s).
For each blockchain → each managed address → each supported token, it calls
`SolanaAdaptor.getTokenTransactionHistory()` and filters for new incoming credits.

```
@Scheduled(fixedDelayString = "${monitoring.poll-interval-ms:30000}")
void poll()
  → for each blockchain cursor in monitoring_cursors
      → for each address in wallets
          → for each token in tokens
              → getTokenTransactionHistory(address, mint, limit=50)
              → filter: status=confirmed, toAddress ∈ managedAddresses, amount > 0
              → MonitoringPersistenceService.persistAndAdvanceCursor()  ← @Transactional
```

Persistence is atomic: `INSERT INTO received_transactions ... ON CONFLICT (tx_hash) DO NOTHING`
+ `UPDATE monitoring_cursors SET last_tx_hash = ?` in the same DB transaction.

**Limitation:** O(addresses × tokens) RPC calls per poll. Acceptable for hundreds of
wallets; does not scale past ~1,000 addresses without hitting RPC rate limits.

### Upgrade path: Yellowstone gRPC (not yet implemented)

> Relevant when managing 1,000,000–10,000,000 user wallets under a single Master Seed.

### Problem

You need to detect every incoming credit to any of your managed addresses, in near
real-time, even if your server goes offline for hours.

Standard WebSocket subscriptions or polling do not scale past ~1,000 addresses. At
1M+ addresses you need a fundamentally different approach.

### Solution: Yellowstone gRPC (Geyser Plugin)

Open **one** persistent gRPC stream to a Solana RPC provider. The stream delivers
every confirmed transaction on the network. Filter locally.

```
Solana Network
     │  (every transaction, ~2,000–4,000 tps)
     ▼
Provider gRPC endpoint (Helius / QuickNode / Triton)
     │  (binary-encoded, filtered by your watchlist server-side)
     ▼
Mac Mini M4 — WalletMonitorService
     │  local HashSet<String> of all managed addresses (~320 MB for 10M × 32B)
     │  parse each tx → check if any account key is in the set
     ▼
PostgreSQL credits table
```

### Recommended providers (2026)

| Provider | Product | Key feature |
|---|---|---|
| **Helius** | **LaserStream** | **24-hour historical replay** (best for recovery) |
| QuickNode | Yellowstone gRPC | Managed add-on, high reliability |
| Triton One | Dragon's Mouth | Original gRPC creators, lowest latency |

### Recovery after outage

```
1. Persist last_processed_slot in DB after each confirmed block.

2. On startup:
   a. Read last_processed_slot from DB.
   b. Get current network slot via rpcClient.getSlot().join().

3. Gap < 24 hours  →  reconnect with fromSlot = last_processed_slot
                       Provider replays missed blocks at high speed (Helius LaserStream).

4. Gap > 24 hours  →  for each *active* address (addresses with recent activity),
                       call rpcClient.getSignaturesForAddress(address, limit) to backfill.
                       "Active" = had a credit in the last 30 days.

5. Always apply idempotency (see below).
```

### Idempotency (deduplication)

Two monitors can see the same transaction. Your DB prevents double-crediting:

```sql
-- Schema
CREATE TABLE credits (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT NOT NULL,
    amount_lamports      BIGINT NOT NULL,
    transaction_signature VARCHAR(100) NOT NULL,
    credited_at          TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE credits
    ADD CONSTRAINT uq_signature UNIQUE (transaction_signature);

-- Insert — duplicate silently ignored
INSERT INTO credits (user_id, amount_lamports, transaction_signature)
VALUES ($1, $2, $3)
ON CONFLICT (transaction_signature) DO NOTHING;
```

### Infrastructure

```
Primary:   Mac Mini M4
           - PM2 process manager (auto-restart on crash)
           - PostgreSQL + Redis

Backup:    $5/mo VPS (geographically separate)
           - Identical WalletMonitorService
           - Writes to same DB → duplicates rejected by UNIQUE constraint
```

---

## 14. Database Schema

All managed wallet state lives in `blockchain_schema` in PostgreSQL (Docker container
`global_banking_db`, database `banking_system`). Liquibase manages migrations from
`src/main/resources/db/changelog/changes/`.

Credentials are in `src/main/resources/application-local.yml` (gitignored).
Active schema is set by `spring.liquibase.default-schema: blockchain_schema` in `application.yml`.

### Tables

| Table | Key columns | Notes |
|---|---|---|
| `blockchains` | `id` (varchar PK), `name`, `network` | Seed: `solana` / devnet |
| `seed_groups` | `id` (uuid PK), `label`, `active`, `next_derivation_index` | One row per 12-word set |
| `wallets` | `id` (uuid PK), `seed_group_id`, `blockchain_id`, `derivation_index`, `public_address` | UNIQUE(seed_group_id, derivation_index, blockchain_id) |
| `wallet_creation_requests` | `id` (uuid PK), `request_id`, `blockchain_id`, `status` | UNIQUE(request_id, blockchain_id) for idempotency |
| `tokens` | PK(id, blockchain_id), `mint_address`, `decimals` | Seeds: usdc + usdt on solana |
| `transactions` | `id` (UUIDv7), `wallet_id`, `to_address`, `amount`, `token_mint`, `status`, `tx_hash` | PENDING → SUBMITTED → CONFIRMED/FAILED; UNIQUE(request_id) |
| `received_transactions` | `id` (UUIDv7), `tx_hash`, `to_address`, `token_id`, `amount`, `blockchain_confirmed_at` | UNIQUE(tx_hash) — deduplication key |
| `monitoring_cursors` | PK `blockchain_id`, `last_tx_hash` | One row per chain; advanced atomically with each insert |

### Migration files

```
001-create-schema.sql           — CREATE SCHEMA blockchain_schema
002-create-tables.sql           — blockchains, seed_groups, wallets
003-rename-schema.sql           — solana_schema → blockchain_schema
004-create-transactions.sql     — set_updated_at() trigger + transactions table (splitStatements:false)
005-alter-seed-groups.sql       — active + next_derivation_index columns
006-create-wallet-creation-requests.sql
007-create-tokens.sql           — tokens + seed USDC/USDT
008-create-received-transactions.sql
009-create-monitoring-cursors.sql — cursor table + seed row for solana
```

> **Liquibase tip:** PL/pgSQL `$` blocks must use `splitStatements:false` in the
> changeset pragma or Liquibase will split on internal semicolons and fail.

---

## 15. Managed Wallet Provisioning Layer

Sits between the REST controllers and `SolanaAdaptorImpl`. All calls are in-process
(no HTTP between Java classes in the same JVM).

### Wallet creation flow

```
POST /api/wallets
  → WalletProvisioningService.createWallet(requestId, blockchainId)
      1. Check wallet_creation_requests for existing requestId (idempotency)
      2. INSERT INTO wallet_creation_requests (status=PENDING)
      3. SeedGroupRepository.claimNextIndex()
         UPDATE seed_groups SET next_derivation_index = next_derivation_index + 1
         WHERE active = true RETURNING id, label, next_derivation_index - 1 AS claimed_index
         — atomic; no SELECT MAX() race condition
      4. SeedGroupRegistry.getMnemonic(seedGroupId)  ← RAM only, never persisted
      5. SolanaAdaptorImpl.deriveAddress(mnemonic, claimedIndex)
      6. WalletRepository.insert(seedGroupId, blockchainId, index, publicAddress)
      7. markFulfilled(requestId, seedGroupId, index, publicAddress)
```

### Token send flow

```
POST /api/wallets/send
  → TransactionProvisioningService.send(requestId, ...)
      1. TransactionRepository.findByRequestId(requestId) — return early if duplicate
      2. WalletRepository.findByAddress(fromPublicAddress, blockchainId) — get wallet_id
      3. TokenRepository.find(tokenId, blockchainId) — get mint_address + decimals
      4. TransactionRepository.insertPending(UUIDv7, wallet_id, to_address, amount, mint)
      5. SeedGroupRegistry.getMnemonic(seedGroupId)
      6. SolanaAdaptorImpl.deriveAddress(mnemonic, derivationIndex) — re-derive signer
      7. SolanaAdaptorImpl.sendToken(signer, toAddress, mintAddress, amount)
      8. TransactionRepository.markSubmitted(id, txHash)
         — on exception: markFailed(id, errorMessage)
```

### Key classes

| Class | Package | Role |
|---|---|---|
| `SeedGroupRegistry` | service | `ConcurrentHashMap<UUID, String>` — mnemonics in RAM only |
| `SeedLoader` | startup | `CommandLineRunner` — prompts operator for mnemonics at startup via Console |
| `WalletProvisioningService` | service | `@Transactional` wallet creation |
| `TransactionProvisioningService` | service | `@Transactional` send lifecycle |
| `MonitoringPersistenceService` | service | `@Transactional` insert credit + advance cursor |
| `WalletMonitoringService` | service | `@Scheduled` polling loop |
| `WalletController` | controller | `POST /api/wallets`, `POST /api/wallets/send` |

### UUIDv7 (monotonic IDs)

All transaction tables use UUIDv7 (time-sortable) via `com.github.f4b6a3:uuid-creator:6.0.0`:

```java
UUID id = UuidCreator.getTimeOrderedEpoch();   // UUIDv7
```

Sorting by `id` is equivalent to sorting by insertion time — no separate `created_at`
index needed for pagination.

---

## 16. Security Principles

| Rule | Why |
|---|---|
| **Never store private keys on disk** | If the server is compromised, only addresses are exposed — no funds |
| **Load seed phrase into RAM only for sweeps** | Sweep = collecting user deposits into one hot wallet. After sweep, let the reference GC |
| **Watch-only mode is the default** | Monitoring credits requires only public addresses |
| **Use hardware enclave or HSM for the master seed** | macOS Secure Enclave or an external HSM protects the seed at rest |
| **Rotate RPC API keys regularly** | Helius/QuickNode API keys are a single point of failure |
| **UNIQUE constraint on transaction_signature** | Prevents double-crediting regardless of retry logic or dual monitors |
| **Validate all addresses before RPC calls** | `PublicKey.fromBase58Encoded()` throws on invalid input — catch and return 400 |

---

## 17. Running Locally

```bash
cd backoffice

# Devnet (default — no real funds)
mvn spring-boot:run

# Mainnet (requires real SOL for fees)
SOLANA_RPC_URL=https://api.mainnet-beta.solana.com mvn spring-boot:run

# With Circle API (optional)
CIRCLE_API_KEY=your-key CIRCLE_ACCOUNT_ID=your-id mvn spring-boot:run

# Run tests (unit tests mock the RPC client — no network required)
mvn test

# Package as fat JAR
mvn package -DskipTests
java -jar target/backoffice-0.1.0-SNAPSHOT.jar
```

### Get devnet SOL for testing

```bash
# Airdrop 1 SOL to a devnet address (only works on devnet)
curl -X POST https://api.devnet.solana.com \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"requestAirdrop",
       "params":["YOUR_ADDRESS", 1000000000]}'

# Or use the Solana CLI
solana airdrop 1 YOUR_ADDRESS --url devnet
```

---

*SDK source: https://github.com/sava-software/sava*
*Solana Foundation Java SDK listing: https://solana.com/developers/guides*
