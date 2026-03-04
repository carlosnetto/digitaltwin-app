# Solana + Java (Sava SDK) — Verified API Reference

All method names here were **verified by runtime testing and `javap` decompilation**
against the actual jars. The original implementation assumed method names from the
Solana JSON-RPC spec; several were wrong. This file records what is actually in the SDK.

---

## How to Verify Any Method Yourself

```bash
# List classes in a sava jar
jar tf ~/.m2/repository/software/sava/sava-rpc/25.3.0/sava-rpc-25.3.0.jar | grep -i <classname>

# Decompile a class to see exact method signatures
javap -p -classpath ~/.m2/repository/software/sava/sava-rpc/25.3.0/sava-rpc-25.3.0.jar \
  software.sava.rpc.json.http.response.TokenAmount
```

---

## Amount Convention: Raw Integer Units

All amounts in the API are **raw integers** — no decimals, no human-readable scaling.

| Currency | Raw unit | 1 human unit |
|---|---|---|
| SOL | lamport | `1_000_000_000` lamports |
| USDC | micro-USDC | `1_000_000` units |
| USDT | micro-USDT | `1_000_000` units |

```bash
# Send 1 USDC
{"amount": 1000000}

# Send 0.5 SOL
{"amount": 500000000}
```

This matches how Solana and most ledger systems represent amounts internally.
Never pass decimal amounts to the API — use raw units only.

---

## Corrections: Assumed vs Actual

These were wrong in the initial implementation and caused
`java.lang.Error: Unresolved compilation problem` at runtime.

| Class | Method we wrote (WRONG) | Actual method | Notes |
|---|---|---|---|
| `TokenAmount` | `.uiAmountString()` | `.amount().longValue()` | `amount()` = raw `BigInteger` |
| `SolanaAccounts` | `.associatedTokenProgramAddress()` | `.associatedTokenAccountProgram()` | Returns `PublicKey` |
| `ProgramDerivedAddress` | `.address()` | `.publicKey()` | Returns `PublicKey` |
| `LatestBlockHash` | `.blockhash()` | `.blockHash()` | Returns `String` (base58), must `Base58.decode()` for `sendTransaction` |
| `TxSig` | `.err()` | `.transactionError()` | Returns `TransactionError`, null = success |
| `TxSig` | `.blockTime()` → `Long` | `.blockTime()` → `OptionalLong` | Use `.isPresent()` / `.getAsLong()` |

---

## Verified Class Reference

### `software.sava.rpc.json.http.response.TokenAmount`
```java
BigInteger amount()    // raw integer — e.g. 1000000 for 1 USDC (6 decimals)
int        decimals()  // e.g. 6 for USDC/USDT
```
Raw balance (our API returns this directly):
```java
long balance = tokenAmount.amount().longValue();
```

---

### `software.sava.rpc.json.http.response.Lamports`
```java
long       lamports()   // raw lamports (1 SOL = 1_000_000_000) — our API returns this directly
long       asLong()     // same as lamports()
BigDecimal toDecimal()  // already divided by 10^9 (use only for display)
BigInteger amount()     // raw as BigInteger
```

---

### `software.sava.rpc.json.http.response.LatestBlockHash`
```java
String blockHash()               // base58-encoded 32-byte blockhash
long   lastValidBlockHeight()
```
**Passing to sendTransaction (expects `byte[]`):**
```java
var latestBlockHash = rpcClient.getLatestBlockHash().join();
rpcClient.sendTransaction(tx, signer, Base58.decode(latestBlockHash.blockHash())).join();
```

---

### `software.sava.rpc.json.http.response.TxSig`
```java
String           signature()        // base58 transaction signature
long             slot()             // confirmed slot
OptionalLong     blockTime()        // epoch seconds — may be absent
TransactionError transactionError() // null = success, non-null = failed
Commitment       confirmationStatus()
String           memo()
```
**Usage:**
```java
.status(sig.transactionError() == null ? "confirmed" : "failed")
.timestamp(sig.blockTime().isPresent()
        ? Instant.ofEpochSecond(sig.blockTime().getAsLong()) : null)
```

---

### `software.sava.core.accounts.SolanaAccounts`
Key program address accessors (all return `PublicKey`):
```java
tokenProgram()                   // SPL Token Program
associatedTokenAccountProgram()  // ATA Program  ← NOT associatedTokenProgramAddress()
invokedSystemProgram()           // AccountMeta for System Program (use in instructions)
invokedTokenProgram()            // AccountMeta for Token Program
systemProgram()                  // PublicKey only
```

---

### `software.sava.core.accounts.ProgramDerivedAddress`
```java
PublicKey    publicKey()   // the derived address  ← NOT .address()
List<byte[]> seeds()
int          nonce()
```
**ATA derivation:**
```java
List<byte[]> seeds = List.of(
        owner.toByteArray(),
        solanaAccounts.tokenProgram().toByteArray(),
        mint.toByteArray());
PublicKey ata = PublicKey.findProgramAddress(seeds,
        solanaAccounts.associatedTokenAccountProgram()).publicKey();
```

---

### `software.sava.core.accounts.Signer`
```java
static byte[]  generatePrivateKeyPairBytes()          // 64 bytes: privKey[0..31] || pubKey[32..63]
static Signer  createFromKeyPair(byte[] keyPairBytes) // takes full 64-byte pair
static Signer  createFromPrivateKey(byte[] privKey)   // takes 32-byte private key
PublicKey      publicKey()
```
**Deriving from mnemonic (SLIP-0010 / BIP44):**
```java
// Path: m/44'/501'/{accountIndex}'/0'
List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));
MnemonicCode.INSTANCE.check(words);          // validates + throws MnemonicException if bad
byte[] seed = MnemonicCode.INSTANCE.toSeed(words, "");
byte[] privKey = slip10Derive(seed, new int[]{0x8000002C, 0x800001F5, 0x80000000, 0x80000000});
Signer signer = Signer.createFromPrivateKey(privKey);
String address    = signer.publicKey().toBase58();
String privateKey = Base58.encode(privKey);  // store this for signing future txs
```
Note: `MnemonicCode.INSTANCE.toSeed()` does NOT throw `MnemonicException` — always call
`.check()` first or the catch block is unreachable and the compiler will warn.

---

### `software.sava.core.tx.Transaction`
```java
static Transaction createTx(AccountMeta feePayer, List<Instruction> instructions)
static Transaction createTx(AccountMeta feePayer, Instruction singleInstruction)
static Transaction createTx(PublicKey feePayer, List<Instruction> instructions)
```

---

### `software.sava.core.accounts.meta.AccountMeta`
```java
static AccountMeta createFeePayer(PublicKey)        // writable signer, pays fees
static AccountMeta createRead(PublicKey)            // read-only, not signer
static AccountMeta createInvoked(PublicKey)         // program being invoked
```

---

## Solana Fee Structure

Fees have two independent components:

### 1. Base fee — fixed by protocol
- **5,000 lamports per signature**, always, regardless of congestion
- Not configurable, not market-driven

### 2. Priority fee — optional, market-driven
- Added as a `ComputeBudgetProgram.setComputeUnitPrice(microLamports)` instruction
- Higher tip = validators prioritize your tx during congestion
- Without it, tx may be delayed or dropped during peak load
- Current implementation does **not** set a priority fee (see TODO.md)

```java
// Example: prepend priority fee instruction before the transfer
Instruction priorityFee = ComputeBudgetProgram.setComputeUnitPrice(
        solanaAccounts, 1_000 /* microLamports per compute unit */);
Transaction tx = Transaction.createTx(feePayer, List.of(priorityFee, transferIx));
```

Helius exposes `POST /v0/rpc/getPriorityFeeEstimate` to get the current market rate
(low / medium / high tiers) — useful for dynamic fee selection.

---

## RPC Provider: Helius vs Public Endpoint

Helius (`https://mainnet.helius-rpc.com/?api-key=<KEY>`) is a **full Solana RPC provider** —
it handles all standard JSON-RPC calls, not just reads.

| Operation | Public mainnet | Helius |
|---|---|---|
| `getBalance` / `getTokenAccountsByOwner` | OK (rate-limited) | OK |
| `getSignaturesForAddress` / `getTransaction` | Needs `tx-detail-delay-ms: 200` | No delay needed (`tx-detail-delay-ms: 0`) |
| `sendTransaction` | OK but unreliable under load | Higher landing rate, routes directly to validators |
| `getLatestBlockhash` | OK | OK |

**Config for Helius** (`application-local.yml`):
```yaml
solana:
  rpc-url: https://mainnet.helius-rpc.com/?api-key=<YOUR_KEY>
  tx-detail-delay-ms: 0
```

**Helius Smart Transaction** (optional upgrade): Helius also exposes a separate REST endpoint
(`POST /v0/transactions/?api-key=...`) called `sendSmartTransaction` that handles auto-retry,
priority fees, and slippage. This is not standard JSON-RPC — it would require a dedicated
REST client call separate from the `SolanaRpcClient`.

---

## Recognizing the Error Pattern

When you see this at runtime, it means the `.class` file was compiled with wrong method names:

```
java.lang.Error: Unresolved compilation problem:
    The method xyz() is undefined for the type Foo
```

**Fix:** `javap -p -classpath <jar> <full.class.Name>` to find the correct method,
then `mvn clean compile` (not just `compile`) to force a full recompile.

---

## Java 25 Warnings (Harmless)

These appear on every startup with GraalVM 25 — they are not errors:

```
WARNING: java.lang.System::load has been called by org.apache.tomcat.jni.Library
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by io.netty.util.internal.PlatformDependent0
```

Both are from third-party libraries (Tomcat, Netty) that haven't yet updated to
Java 25's restricted/removed APIs. They do not affect functionality.
