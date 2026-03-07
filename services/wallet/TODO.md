# digitaltwinapp-api TODO

## Security

### Ownership enforcement on API endpoints
Current session auth correctly blocks unauthenticated requests, but service methods
don't explicitly verify that the requested resource belongs to the logged-in user.
A logged-in user who knows another user's currencyCode or account ID could potentially
craft a request for another user's data.

Add an ownership check at the service layer on every endpoint that takes a
user-scoped parameter:

```java
boolean owns = userAccountRepository.existsByUserIdAndCurrencyCode(
    currentUser.getUserId(), currencyCode);
if (!owns) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
```

No architectural change — purely additive guards inside existing service methods.

### API Key support (future — developer access)
Allow users to generate API keys for programmatic access (like Gemini, Stripe, GitHub).
Coexists with the current session auth as a parallel Spring Security path:

```
Browser    → session cookie   → Spring Session validates
API client → X-Api-Key header → DB/cache lookup validates
```

Requirements when implementing:
- Store a **hash** of the key in the DB (never the raw key — user sees it once on
  generation, same model as GitHub/Stripe)
- Scopes — read-only vs full access, per-currency restrictions
- Rate limiting — API key callers can be scripts, not humans
- Key revocation and rotation
- Audit log — which key called what and when
- Ownership check (same as above) is even more critical here

---

## Performance / Infrastructure

### Redis session store (replace PostgreSQL for sessions)
Spring Session currently stores sessions in PostgreSQL. Every API call does:
```
cookie → SELECT * FROM SPRING_SESSION WHERE SESSION_ID = ? → valid/invalid
```

At current scale (handful of @matera.com users) this is negligible. If load grows,
swap the session store from JDBC to Redis — Spring Session abstracts it completely,
zero changes to controllers or services:

- Add `spring-session-data-redis` dependency
- Replace `spring-session-jdbc` config with Redis connection
- Sessions live in memory: microsecond lookups vs PostgreSQL round-trips

Same model applies to API key validation — cache key→user_id in Redis rather than
hitting the DB on every call.

### Why not JWT? (decision record)
JWT was considered and rejected in favor of server-side sessions for the following reasons:

1. **Spring Session is enterprise-grade** — httpOnly cookie, SameSite=Lax (CSRF
   protection), true server-side logout (invalidate the DB/Redis row and the token
   is immediately dead). JWT cannot be truly revoked before expiry without a
   blocklist, which defeats the stateless benefit.

2. **No use case for stateless tokens here** — JWT is valuable for mobile apps,
   microservices passing identity across service boundaries, or public third-party
   APIs. This API is called only by our own browser frontend over HTTPS.

3. **Post-quantum cryptography (PQC) makes JWT worse over time** — JWT's core
   value proposition is "signature verification is cheaper than a DB lookup."
   PQC algorithms like CRYSTALS-Dilithium produce signatures of 1–5 KB and keys
   of 1–2 KB, with significantly heavier verification cost. At scale this becomes
   real CPU pressure on every API call, plus HTTP overhead of carrying multi-KB
   tokens in every request header. Server-side sessions with Redis are unaffected
   by PQC — the session ID in the cookie stays a small random string forever;
   cryptography lives at the TLS layer, not the application layer.

**If API keys are added in the future**, the same reasoning applies — validate via
a fast cache lookup, not a signed token.

---

## Transaction Metadata (partially implemented)

The DB layer is fully in place (migrations 014–019, backfill service, `transaction_metadata`
table). The following pieces are NOT yet built.

### SchemaRegistryService (validation, not built)
Planned: a `@Service` holding a `ConcurrentHashMap<Integer, JsonSchema>` of pre-compiled
networknt Draft 2020-12 schemas, loaded at `@PostConstruct` from `digitaltwinapp.transaction_schemas`
and refreshable at runtime via an `AtomicReference` swap (zero-downtime schema updates).

Until this is built, metadata blobs are written to `transaction_metadata` without validation.
The JSON schemas seeded in `transaction_schemas` are the authoritative contract — implement
validation before exposing metadata to external consumers.

Dependency to add when implementing:
```xml
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.5.6</version>  <!-- check for latest -->
</dependency>
```

### TransactionDisplayService (template resolution, not built)
`transaction_schema_i18n` holds `summary_data` (string template) and `detailed_data`
(JSON array of `{"label":"...", "value":"${path}"}` objects) seeded for `en` and `pt-BR`.

Planned: a service that accepts a `trans_code`, `lang`, and the `metadata` JSONB blob,
resolves `${dot.path}` placeholders against the blob, and returns a structured display
object for the API response. Without this, the i18n templates are data-only and unused.

### schema_version is always 1 — versioning mechanism deferred
`transaction_metadata.schema_version` defaults to 1 and is hardcoded as `1` in all
current inserts (backfill service and, eventually, ConversionService / WalletService).

Every metadata blob already carries `"schema_version": 1` as its first field (enforced
by `insertMetadata()` via `LinkedHashMap` ordering and validated by the JSON schemas in
`transaction_schemas`). This means all data currently stored in the ledger is already
self-tagged as v1 — when versioning arrives, no rework is needed on existing rows and
no migration of stored JSONB blobs is required. New v2 transactions simply write
`"schema_version": 2`; v1 rows stay valid and queryable as-is.

When a breaking change to a metadata shape is needed:
1. Add a `schema_version INT NOT NULL DEFAULT 1` column to `transaction_schemas`
2. Insert the new schema as a second row with `schema_version = 2` (PK becomes
   `(trans_code, schema_version)` — migration required)
3. Write new transactions with `schema_version = 2`
4. Old rows remain queryable at `schema_version = 1`

Do not implement until a v2 schema is actually required.

### Forward metadata capture (ConversionService + WalletService, not built)
New transactions created after the backfill window do NOT yet write to `transaction_metadata`.
Before removing `TransactionMetadataBackfillService`, update:

- `ConversionService` — insert 2 rows (debit + credit) into `transaction_metadata` after
  the 4 mini-core transaction calls succeed
- `WalletService.p2pTransfer()` — insert 2 rows (sent + received) after the mini-core calls

Use `ON CONFLICT (ledger_id) WHERE ledger_id IS NOT NULL DO NOTHING` (same as backfill)
so a double-post is harmless.

**Self-contained blob requirement:** every metadata map passed to the insert helper must
have `schema_version` as its **first key** (use `LinkedHashMap`, put `schema_version` before
all domain fields). This mirrors what `TransactionMetadataBackfillService.insertMetadata()`
already does. The blob must be self-describing so that a ledger holding the raw JSON can
reconstruct the display string and validate the payload independently, years later, without
access to our internal tables.

### Remove TransactionMetadataBackfillService
Once forward capture is in place and all environments confirm:
```sql
SELECT COUNT(*) FROM digitaltwinapp.transaction_metadata;
-- equals 2× p2p_transactions + 2× conversions rows
```
...and the backfill log reports `0 new rows` on startup, delete
`TransactionMetadataBackfillService.java`.
