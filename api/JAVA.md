# JAVA.md — Java Conventions for digitaltwinapp-api

Java 21 target. Spring Boot 3.3.x. No Lombok. No JPA.

---

## Field declaration order

```java
// 1. Logger — always first
private static final Logger log = LoggerFactory.getLogger(ClassName.class);

// 2. Constants — grouped by concept, aligned on =
private static final int TX_BUY_FIAT_DEBIT    = 50005;   // Crypto Purchase Payment
private static final int TX_BUY_CRYPTO_CREDIT  = 40003;   // Crypto Purchase Received
private static final long POOL_USER_ID         = 1L;

// 3. Dependencies
private final JdbcTemplate   jdbc;
private final MiniCoreClient miniCoreClient;
```

Align `=` signs within a group using spaces. Trailing comment explains the constant's domain meaning (never just restates the name).

---

## Constructor injection — always, no @Autowired

```java
public MyService(JdbcTemplate jdbc, MiniCoreClient miniCoreClient) {
    this.jdbc           = jdbc;
    this.miniCoreClient = miniCoreClient;
}
```

Align `=` signs on the right side of assignments when there are multiple fields.

---

## Logging

```java
private static final Logger log = LoggerFactory.getLogger(ClassName.class);
```

- `log.info` — normal flow milestones
- `log.warn` — recoverable issues (partial failures, retries, skipped rows)
- `log.error` — something broke that needs attention; always include `e` as last arg so the stack trace is captured
- Never `System.out` or `e.printStackTrace()`

```java
// Good — structured, searchable
log.info("BACKFILL [p2p]: processed {} records → {} rows inserted", p2pRows.size(), inserted);
log.warn("mini-core getAccount failed for account_id={}: {}", accountId, e.getMessage());
log.error("SchemaRegistry: failed to load schemas: {}", e.getMessage(), e);

// Bad
log.info("Done");
System.out.println("Error: " + e);
```

Prefix log messages with a tag when the class emits many related lines (`BACKFILL [p2p]:`, `SchemaRegistry:`, etc.).

---

## Money — BigDecimal only

Never use `double` or `float` for amounts. Always `BigDecimal`.

```java
// Truncate (never round up) to the currency's declared decimal_places from the DB
BigDecimal fromAmountBD = BigDecimal.valueOf(fromAmount).setScale(fromDecimalPlaces, RoundingMode.DOWN);
BigDecimal toAmountBD   = fromAmountBD.multiply(rate).setScale(toDecimalPlaces, RoundingMode.DOWN);
```

Always read `decimal_places` from `digitaltwinapp.currencies` — never hardcode (fiat=2, crypto=6 is wrong for JPY=0, etc.).

---

## Collections — prefer immutable

```java
Map.of(...)         // small literal maps
Map.copyOf(map)     // defensive copy of a mutable map — use for in-memory cache snapshots
List.of(...)        // small literal lists
List.copyOf(list)   // defensive copy
stream.toList()     // immutable terminal (Java 16+)
```

Use `LinkedHashMap` when insertion order must be preserved (e.g., JSON fields that humans read).

---

## var — for-each loops and obvious right-hand sides only

```java
// Good — type is obvious from the right-hand side
var body = new HashMap<String, Object>();
for (var row : rows) { ... }

// Bad — obscures the type
var result = service.convert(...);
```

---

## Java 21 features in use

### Records for DTOs and internal data carriers
```java
// Public API type (model package or inner public record)
public record ResolvedField(String label, String value) {}

// Internal — private record inside the class that owns it
private record FieldTemplate(String label, List<Segment> segments) {}
private record TxOwner(String email, String name, String currencyCode) {}
```

### Sealed interfaces + record patterns
```java
sealed interface Segment permits Literal, PathRef {}
record Literal(String text)   implements Segment {}
record PathRef(String[] path) implements Segment {}

// Pattern matching in switch (Java 21, no preview)
switch (seg) {
    case Literal(var text) -> sb.append(text);
    case PathRef(var path) -> { ... }
}
```

### instanceof pattern matching
```java
if (!(current instanceof Map<?, ?> m)) return null;
current = ((Map<String, Object>) m).get(key);
```

---

## Null-signaling conventions

External calls that can fail use sentinel values so callers decide how to handle:

| Return type | Failure sentinel | Used in |
|---|---|---|
| `long` (ID) | `-1L` | `MiniCoreClient.createTransaction`, `createAccount` |
| `Map<String, Object>` | `null` | `MiniCoreClient.getAccount` |
| `List<...>` | `List.of()` | `MiniCoreClient.getTransactions` |
| `Optional<T>` | `Optional.empty()` | `SchemaRegistryService.validate`, `TransactionDisplayService.resolve` |

Never return `null` from a method that returns `List` or `Optional` — use empty variants.

---

## Exception semantics

| Exception | Meaning | Controller response |
|---|---|---|
| `IllegalArgumentException` | Business rule violation (insufficient balance, user not found, bad input) | 400 Bad Request |
| `IllegalStateException` | System invariant broken (no rate found, account missing) | 500 Internal Server Error |
| `RuntimeException` | Unrecoverable failure after partial work | 500 Internal Server Error |

Services throw; controllers catch and translate. Never catch in the service and swallow silently.

The one exception: `ApplicationRunner.run()` — always wrap in try/catch so a backfill or startup task never crashes the API.

---

## @SuppressWarnings("unchecked")

Used only when casting raw types returned by untyped JSON deserialization (RestClient → `Map.class` / `List.class`). Place it on the narrowest scope — the local variable declaration, not the whole method.

```java
@SuppressWarnings("unchecked")
Map<String, Object> response = restClient.post()...body(Map.class);
```

---

## Javadoc

Write Javadoc only on public methods whose behaviour is non-obvious. Document the contract (what it returns, edge cases), not the implementation.

```java
/**
 * Validates a metadata map against the registered schema for transCode.
 *
 * @return Optional.empty()    — no schema registered; payload accepted as-is.
 *         Optional.of(empty)  — schema found; payload is VALID.
 *         Optional.of(errors) — schema found; payload is INVALID.
 */
```

Skip Javadoc on private helpers, getters, and methods whose name + signature are self-documenting.

---

## Section separators inside long methods

Use `──` comments to divide logical phases inside a method. Do not add headers to every 3-line method.

```java
// ── 1. Resolve user_id ─────────────────────────────────────────────
// ── 2. Resolve currency IDs + is_fiat + decimal_places ────────────
// ── 3. Look up exchange rate ───────────────────────────────────────
```

---

## UUIDs

Use `UuidCreator.getTimeOrderedEpoch()` (UUIDv7) for IDs that are stored in the DB and need time-sortability. Never `UUID.randomUUID()` for new entity IDs.

```xml
<dependency>
    <groupId>com.github.f4b6a3</groupId>
    <artifactId>uuid-creator</artifactId>
    <version>6.0.0</version>
</dependency>
```

---

## PostgreSQL JSONB with JdbcTemplate

**Reading:** cast to text in SQL so the JDBC driver returns a `String`, not a `PGobject`.
```java
jdbc.queryForList("SELECT col::text AS col FROM ...")
```

**Writing:** use `PGobject` — the `?::jsonb` syntax does not work with PreparedStatement placeholders.
```java
PGobject jsonb = new PGobject();
jsonb.setType("jsonb");
jsonb.setValue(objectMapper.writeValueAsString(metadata));
jdbc.update("INSERT INTO t (col) VALUES (?)", jsonb);
```

**Idempotent inserts:**
```sql
INSERT INTO t (...) VALUES (...)
ON CONFLICT (ledger_id) WHERE ledger_id IS NOT NULL DO NOTHING
```

The partial `WHERE` clause must match the partial unique index definition exactly.
