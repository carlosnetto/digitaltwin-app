# Transaction Metadata System

Mini-core is the ledger. It stores the raw financial facts — amount, date, direction, transaction code — but nothing about the people or assets involved. That context lives here, in the `digitaltwinapp` schema, linked to the ledger by ID.

This document covers the full system: database design, multi-language display templates, JSON schema validation, and the two caching layers that keep the user experience fast.

---

## The problem

A ledger entry for a P2P transfer tells you: `account 1000100, debit, $25.00, code 20026`. It does not tell you who received the money, in which currency, or how to phrase that in Portuguese. That enrichment is what this system provides.

The same applies to conversions (from/to amounts, rate), external wallet sends (destination address, blockchain, tx hash), and any future transaction type. Mini-core is deliberately kept as a simple, replaceable ledger. All domain meaning lives in digitaltwinapp.

---

## Database tables

Four tables form the system. All live in the `digitaltwinapp` schema.

```
transaction_codes          ← curated subset of mini-core codes relevant to our domain
      │
      ├── transaction_schemas      ← JSON Schema (Draft 2020-12) per code
      ├── transaction_schema_i18n  ← display templates per code × language
      │
transaction_metadata       ← one row per ledger transaction, linked by ledger_id
```

### `transaction_codes`

```sql
CREATE TABLE transaction_codes (
    code  INTEGER      NOT NULL,
    label VARCHAR(100) NOT NULL,
    CONSTRAINT transaction_codes_pkey PRIMARY KEY (code)
);
```

A curated, app-layer subset of mini-core's 86 codes. Not a mirror — only the codes that carry enriched metadata or have display templates. Seeded in migration `014`.

| Code range | Direction |
|---|---|
| 10xxx | Credit (money in) |
| 20xxx | Debit (money out) |
| 30xxx | Fee |
| 40xxx | Crypto credit |
| 50xxx | Crypto debit |

### `transaction_schemas`

```sql
CREATE TABLE transaction_schemas (
    trans_code  INTEGER NOT NULL,
    json_schema JSONB   NOT NULL,
    CONSTRAINT transaction_schemas_pkey PRIMARY KEY (trans_code),
    CONSTRAINT transaction_schemas_fk   FOREIGN KEY (trans_code)
        REFERENCES transaction_codes(code)
);
```

One JSON Schema (Draft 2020-12) per transaction code. Describes the exact shape of the metadata blob that must be stored in `transaction_metadata`. Seeded in migration `018`.

A transaction code without a row here = pass-through (no validation). This is intentional — not every code needs to enforce a metadata shape.

### `transaction_schema_i18n`

```sql
CREATE TABLE transaction_schema_i18n (
    trans_code    INTEGER      NOT NULL,
    lang          VARCHAR(10)  NOT NULL,
    summary_data  TEXT         NOT NULL,
    detailed_data JSONB        NOT NULL,
    CONSTRAINT transaction_schema_i18n_pkey PRIMARY KEY (trans_code, lang),
    CONSTRAINT transaction_schema_i18n_fk   FOREIGN KEY (trans_code)
        REFERENCES transaction_codes(code)
);
```

Display templates per code × language. Language tags follow BCP-47 (`en`, `pt-BR`). Seeded in migrations `016` and `017`.

- `summary_data` — a single template string for the transaction list row.
- `detailed_data` — a JSON array of `{"label": "...", "value": "..."}` objects for the detail screen.

Both fields use `${dot.path}` placeholder syntax, resolved at runtime against the metadata blob for that specific transaction. The surrounding text is locale-specific; the paths are language-agnostic.

### `transaction_metadata`

```sql
CREATE TABLE transaction_metadata (
    id             UUID        NOT NULL,
    trans_code     INTEGER     NOT NULL,
    ledger_id      VARCHAR(64) NULL,   -- mini-core transaction_id (numeric, stored as string)
    ledger_uuid    UUID        NULL,   -- reserved for future ledger systems that use UUIDs
    schema_version INTEGER     NOT NULL DEFAULT 1,
    metadata       JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ...
    CONSTRAINT transaction_metadata_ledger_check CHECK (
        (ledger_id IS NOT NULL AND ledger_uuid IS NULL) OR
        (ledger_id IS NULL     AND ledger_uuid IS NOT NULL)
    )
);

CREATE UNIQUE INDEX transaction_metadata_ledger_id_idx
    ON transaction_metadata (ledger_id) WHERE ledger_id IS NOT NULL;
```

One row per ledger transaction. The `id` is a UUIDv7 (time-sortable). The `ledger_id` is the mini-core `transaction_id` cast to string. The mutually-exclusive check constraint and partial unique indexes ensure exactly one of `ledger_id` / `ledger_uuid` is populated — supporting mini-core today and a different ledger in the future without a schema change.

`schema_version` is always `1` for now. See [versioning](#schema-versioning-deferred).

---

## Multi-language display templates

### Template syntax

Both `summary_data` and `detailed_data` values use `${dot.path}` placeholders. Paths are resolved against the transaction's `metadata` JSONB blob at runtime. Dotted paths navigate nested objects (`${custom.reference}`).

```
summary_data:   "Sent ${amount} ${currency} to ${recipient_name}"
detailed_data:  [
  {"label": "Recipient", "value": "${recipient_name}"},
  {"label": "Email",     "value": "${recipient_email}"},
  {"label": "Amount",    "value": "${amount} ${currency}"}
]
```

A single value can contain multiple placeholders mixed with literal text, e.g. `"${amount} ${currency}"`.

### Seeded languages and codes

`en` and `pt-BR` are seeded for all 12 transaction codes:

| Code  | Operation side | en summary example |
|-------|----------------|-------------------|
| 20026 | P2P Sent | `Sent ${amount} ${currency} to ${recipient_name}` |
| 10027 | P2P Received | `Received ${amount} ${currency} from ${sender_name}` |
| 50005 | Buy (fiat debit) | `Purchased ${to_amount} ${to_currency} for ${from_amount} ${from_currency}` |
| 40003 | Buy (crypto credit) | `Purchased ${to_amount} ${to_currency}` |
| 50003 | Sell (crypto debit) | `Sold ${from_amount} ${from_currency} for ${to_amount} ${to_currency}` |
| 40005 | Sell (fiat credit) | `Sold ${from_amount} ${from_currency}` |
| 50002 | Convert crypto→crypto (debit) | `Converted ${from_amount} ${from_currency} to ${to_currency}` |
| 40002 | Convert crypto→crypto (credit) | `Converted ${from_currency} to ${to_amount} ${to_currency}` |
| 50006 | Convert fiat→fiat (debit) | `Converted ${from_amount} ${from_currency} to ${to_currency}` |
| 40006 | Convert fiat→fiat (credit) | `Converted ${from_currency} to ${to_amount} ${to_currency}` |
| 50004 | Sent to External Wallet | `Sent ${amount} ${currency} to ${blockchain}` |
| 40004 | Received from External Wallet | `Received ${amount} ${currency} via ${blockchain}` |

---

## Metadata schemas (what goes in the blob)

Each transaction code has a registered JSON Schema enforcing the shape of its metadata blob.

| Code(s) | Required fields | Optional fields |
|---|---|---|
| 20026 (P2P Sent) | `recipient_email`, `recipient_name`, `amount`, `currency` | `custom{}` |
| 10027 (P2P Received) | `sender_email`, `sender_name`, `amount`, `currency` | `custom{}` |
| 50005, 40003, 50003, 40005, 50002, 40002, 50006, 40006 (conversions) | `from_amount`, `from_currency`, `to_amount`, `to_currency`, `rate` | `custom{}` |
| 50004 (Sent to Ext. Wallet) | `destination_address`, `blockchain`, `amount`, `currency` | `tx_hash` (absent while pending), `custom{}` |
| 40004 (Received from Ext. Wallet) | `blockchain`, `tx_hash`, `amount`, `currency` | `source_address` (not all chains expose sender), `custom{}` |

Design rules applied uniformly to all schemas:
- `additionalProperties: false` at root — no undeclared fields accepted
- `custom: { type: object, additionalProperties: true }` — escape hatch for caller-specific fields without breaking validation
- Amounts use `{ type: "number", exclusiveMinimum: 0 }` — Jackson serialises `BigDecimal` as a JSON number, so no string encoding

---

## Linking to the ledger

Mini-core uses auto-incrementing `BIGINT` transaction IDs. These are stored in `ledger_id` as `VARCHAR(64)`. The choice of `VARCHAR(64)` rather than `BIGINT` is intentional: future ledger systems may use non-numeric identifiers (e.g. `TXN-20260307-001234` or a UUID string), and this column must survive a ledger replacement without a schema change.

The partial unique index (`WHERE ledger_id IS NOT NULL`) enforces one metadata row per ledger transaction without penalising the nullable column.

### Linking in practice

When a P2P transfer completes:

```
mini-core → debitTxId  = 100042   (sender's DEBIT  tx, code 20026)
mini-core → creditTxId = 100043   (recipient's CREDIT tx, code 10027)

transaction_metadata row 1: ledger_id = "100042", trans_code = 20026,
    metadata = { recipient_email, recipient_name, amount, currency }

transaction_metadata row 2: ledger_id = "100043", trans_code = 10027,
    metadata = { sender_email, sender_name, amount, currency }
```

When a conversion completes, only the two user-facing ledger IDs (debit + credit) get metadata rows. The two pool-side IDs are internal plumbing and carry no user-visible metadata.

---

## Caching — two independent layers

### Layer 1 — `SchemaRegistryService`: pre-compiled JSON schemas

**What it caches:** `JsonSchema` objects compiled from the `transaction_schemas` table. Compilation (parsing the schema string, resolving `$ref`, compiling regex patterns) is expensive and identical for every validation call. Doing it once at startup pays the cost once.

**How it works:**

```java
private volatile Map<Integer, JsonSchema> schemas = Map.of();

@PostConstruct
public void load() {
    Map<Integer, JsonSchema> compiled = new HashMap<>();
    // SELECT trans_code, json_schema::text FROM transaction_schemas
    // factory.getSchema(json) — compiles once
    schemas = Map.copyOf(compiled);   // immutable, replaced atomically
}
```

`volatile` + `Map.copyOf()` gives an atomic snapshot swap. In-flight `validate()` calls see either the old or the new map, never a partially-constructed one. No locks needed.

`refresh()` is a public method that can be wired to an admin endpoint to reload schemas at runtime — for example after inserting a new schema row without restarting the API.

**Cost per validation call:** one `volatile` read + one `HashMap.get()` + a `JsonNode` tree traversal. No I/O, no parsing.

### Layer 2 — `TransactionDisplayService`: i18n template cache

**What it caches:** Pre-parsed `DisplayTemplate` objects per `(language, transCode)`. Parsing means walking the template string once and producing an immutable list of typed segments (`Literal("Sent ")`, `PathRef(["amount"])`, `PathRef(["currency"])`, etc.). Runtime resolution is then a plain loop — no regex, no re-parsing per request.

**Loading strategy:**

| Language | When loaded |
|---|---|
| `en` | Eagerly at `@PostConstruct init()` — always ready |
| Any other language | Lazily on first request for that language |

```java
private final ConcurrentHashMap<String, Map<Integer, DisplayTemplate>> cache = new ConcurrentHashMap<>();

// Lazy load — computeIfAbsent returns null on DB failure
// ConcurrentHashMap does NOT store null values → next request retries
Map<Integer, DisplayTemplate> templates = cache.computeIfAbsent(lang, l -> {
    Map<Integer, DisplayTemplate> loaded = loadLanguage(l);
    return loaded;   // null if DB failed → not cached → retry next call
});
```

A language with zero rows in the DB returns an empty `Map.copyOf()` which IS cached (correct — the language exists, it just has no templates). A DB failure during load returns `null`, which is NOT cached — the next request will retry the DB automatically.

**Language fallback:** if no template exists for `(transCode, requested-lang)`, the service falls back to `en` before returning `Optional.empty()`.

**Cost per resolution call:** one `ConcurrentHashMap.get()` + O(n) string concatenation where n is the number of template segments (typically 3–6). No I/O, no regex.

### Layer 3 — `WalletService.metadataCache`: recent transaction metadata

**What it caches:** The deserialized `metadata` blob + `transCode` for each transaction recently seen in the list. Stored as raw `Map<String, Object>` (not as resolved strings) so the cache is language-agnostic — the same cached entry serves any language without re-fetching.

**TTL:** 5 minutes per entry, rolling. Each entry stores its own `expiresAt` timestamp set at the time of fetch. Expiry is checked lazily on access. Expired entries are overwritten on the next miss cycle — there is no background eviction thread.

**The sliding wallet problem — how the cache helps:**

When a user swipes between wallets (USD → USDC → USDT → back to USD), the transaction list for each wallet is fetched fresh from mini-core on every swipe. Without a cache, each swipe would also trigger a `WHERE ledger_id IN (...)` query against `transaction_metadata`. With the cache, only ledger IDs not seen before (or expired) hit the DB. The rest are served from memory.

```
First slide to USD:   50 mini-core txs → 50 cache misses → 1 IN query (50 IDs) → 50 entries cached
Slide to USDC:        50 mini-core txs → ~45 cache misses → 1 IN query (45 IDs) → 45 new entries
Slide back to USD:    50 mini-core txs → 50 cache hits   → 0 DB queries
Tap a USD transaction:                   1 cache hit      → 0 DB queries
```

**Partition logic in `fetchSummaries()`:**

```
ledger IDs from mini-core (up to 50)
        │
        ├── cache hit (not expired)  → resolve from cache, no DB
        │
        └── cache miss or expired   → single batch IN query for all misses
                                       → store results in cache
                                       → resolve from cache
```

The DB is hit at most once per `getTransactions()` call regardless of how many IDs are missing.

**Detail endpoint and the cache:**

`GET /api/wallets/transactions/{ledgerId}` checks the cache first. If the user tapped a transaction they already saw in the list (the common case), the metadata is already cached and the detail response involves zero DB queries:

```
cache hit  → displayService.resolve(transCode, lang, metadata) → ResolvedDisplay
cache miss → single queryForMap WHERE ledger_id = ?
             → cache the result
             → resolve
```

**Thread safety:** `ConcurrentHashMap.put()` is atomic. Two concurrent requests for the same missing ID may both query the DB and both write. The last write wins. Both writes produce the same data, so this race is harmless — no lock needed.

---

## API endpoints

### Transaction list (enriched)

```
GET /api/wallets/transactions?currencyCode=USD&lang=en
```

Returns up to 50 mini-core transactions, each optionally enriched with a `"summary"` field:

```json
[
  {
    "transaction_id": 100042,
    "transaction_code": 20026,
    "transaction_description": "P2p sent",
    "amount": 25.00,
    "direction": "DEBIT",
    "summary": "Sent 25.00 USD to John Doe"
  },
  {
    "transaction_id": 100031,
    "transaction_code": 10006,
    "transaction_description": "Direct deposit - payroll",
    "amount": 3500.00,
    "direction": "CREDIT"
  }
]
```

Transactions without a `transaction_metadata` row (e.g. payroll deposits, ATM withdrawals) are returned without a `"summary"` field. The UI falls back to `transaction_description` in that case.

`lang` defaults to `"en"`. Pass `lang=pt-BR` for Portuguese summaries.

### Transaction detail

```
GET /api/wallets/transactions/{ledgerId}?lang=en
```

Single-transaction lookup. Returns the full resolved display — summary + labeled fields:

```json
{
  "summary": "Sent 25.00 USD to John Doe",
  "fields": [
    { "label": "Recipient", "value": "John Doe" },
    { "label": "Email",     "value": "john.doe@example.com" },
    { "label": "Amount",    "value": "25.00 USD" }
  ]
}
```

Returns `404` if no metadata row exists for this transaction. The UI should display the raw ledger data it already has from the list response.

`{ledgerId}` is the mini-core `transaction_id` as a string (e.g. `/api/wallets/transactions/100042`).

---

## Historical backfill

`TransactionMetadataBackfillService` is a temporary `ApplicationRunner` (marked `@Order(10)`) that runs once at startup to populate `transaction_metadata` for all transactions that existed before this system was introduced.

It uses `ON CONFLICT (ledger_id) WHERE ledger_id IS NOT NULL DO NOTHING` so every startup is safe to run — already-inserted rows are silently skipped. Cost on a warm system is one `SELECT` per user account against mini-core.

**Remove it when:**
```sql
SELECT COUNT(*) FROM digitaltwinapp.transaction_metadata;
-- equals 2× COUNT(*) FROM p2p_transactions
--       + 2× COUNT(*) FROM conversions
```
...and the startup log reports `0 new rows`.

See `TODO.md` for the full removal checklist, including the requirement to add forward-capture to `ConversionService` and `WalletService.p2pTransfer()` before removing the backfill.

---

## Adding a new transaction code

1. **Add the code** to `transaction_codes` in a new Liquibase migration.
2. **Add the JSON schema** to `transaction_schemas` in the same migration. Follow the schema design rules: `additionalProperties: false`, `custom` escape-hatch, amounts as `number` not `string`.
3. **Add i18n templates** to `transaction_schema_i18n` for `en` (and `pt-BR` if needed). Test your `${path}` placeholders against a real metadata blob.
4. **Write the metadata** in the service that creates the transaction (`ConversionService`, `WalletService.p2pTransfer`, or a new service). Insert into `transaction_metadata` immediately after the mini-core `createTransaction()` call succeeds.
5. The `SchemaRegistryService` will pick up the new schema on next startup (or after calling `refresh()`). The `TransactionDisplayService` will pick up the new templates on the next request for that language (lazy load).

---

## Self-contained blobs — schema_version inside the payload

Every metadata blob starts with `schema_version` as its **first field**:

```json
{
  "schema_version": 1,
  "recipient_email": "john.doe@example.com",
  "recipient_name": "John Doe",
  "amount": 25.00,
  "currency": "USD"
}
```

**Why first?** Because if this payload is ever forwarded to an external ledger, exported to a data lake, archived by a partner bank, or retrieved a decade from now on a completely different system, the reader can identify the exact JSON schema and i18n display templates that apply — without any dependency on our `transaction_schemas` or `transaction_schema_i18n` tables. The blob is self-describing. The bank holding the ledger can independently validate and reconstruct the display string for any transaction, at any point in the future.

`schema_version` is also stored in the `transaction_metadata.schema_version` column for fast DB-level queries without parsing JSON. Both always hold the same value.

`schema_version` is declared as a required field in every registered JSON Schema (patched into the DB by migration `020`), so `SchemaRegistryService.validate()` will reject any blob that omits it.

## Schema versioning (deferred)

The `schema_version` field is always `1`. Every blob written to `transaction_metadata` — by the backfill service and, eventually, by `ConversionService` and `WalletService.p2pTransfer()` — carries `"schema_version": 1` as its first key (enforced by `insertMetadata()` via `LinkedHashMap` ordering). The column and blob field exist now because they cannot be accurately back-filled once historical rows exist.

**No rework needed for existing data.** All rows currently stored are already self-tagged as v1. When a v2 schema is introduced, new transactions write `"schema_version": 2`; v1 rows remain valid and queryable as-is — zero migration of stored JSONB blobs required.

The versioning mechanism — selecting the right schema and templates for a given version — is not yet implemented.

When a breaking change to a metadata shape is needed:
1. Add `schema_version INT NOT NULL DEFAULT 1` to `transaction_schemas` (making the PK `(trans_code, schema_version)` — migration required)
2. Insert the new schema as a second row with `schema_version = 2`; old rows remain queryable at `schema_version = 1`
3. Write new transactions with `schema_version = 2` in both the blob and the column
4. `TransactionDisplayService` will need to accept `schema_version` and look up the matching i18n template row

Do not implement until a v2 schema is actually required. The full plan is in `TODO.md`.
