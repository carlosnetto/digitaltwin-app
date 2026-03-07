# SPRING.md — Spring Boot Conventions for digitaltwinapp-api

Spring Boot 3.3.x, Spring Framework 6.x, Java 21. No JPA. No Lombok.

---

## Stereotype selection

| Annotation | When to use |
|---|---|
| `@Service` | Business logic: services that orchestrate DB calls, external clients, domain rules |
| `@Component` | Infrastructure: HTTP clients, adapters, utilities that are not services |
| `@RestController` | REST endpoints. Never `@Controller + @ResponseBody` |
| `@Configuration` | Bean factory classes (CORS, security, etc.) |

---

## Controllers

### Response type
Always `ResponseEntity<?>`. Never return raw objects — it makes auth checks and error shapes consistent.

```java
@GetMapping("/api/wallets")
public ResponseEntity<?> getWallets(HttpSession session) {
    UserInfo user = (UserInfo) session.getAttribute("user");
    if (user == null) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Not authenticated"));
    }
    return ResponseEntity.ok(walletService.getWalletsForUser(user.email()));
}
```

### Auth check pattern
Every handler that requires authentication checks the session at the top, before touching any service. Return 401 with `{"error": "Not authenticated"}`.

```java
UserInfo user = (UserInfo) session.getAttribute("user");
if (user == null) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
}
```

Handlers that only need to know the user is logged in (no user data needed) can use the short form:
```java
if (session.getAttribute("user") == null) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
}
```

### Exception translation
Controllers catch service exceptions and translate them to HTTP status codes. Services throw; controllers decide the status.

```java
try {
    ConversionResultDto result = conversionService.convert(...);
    return ResponseEntity.ok(result);
} catch (IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
} catch (Exception e) {
    log.error("Conversion failed for user={}: {}", user.email(), e.getMessage());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Conversion failed: " + e.getMessage()));
}
```

### Input validation
Validate obvious constraints (amount > 0) in the controller before calling the service. Business rule validation (insufficient balance, user not found) belongs in the service.

```java
if (req.amount() <= 0) {
    return ResponseEntity.badRequest().body(Map.of("error", "Amount must be greater than zero"));
}
```

### Error response shape
Always `Map.of("error", "human readable message")`. Never expose stack traces. Never vary the key name.

---

## Database — JdbcTemplate only

**No JPA/Hibernate.** All DB access goes through `JdbcTemplate`. Reasons: predictable SQL, no N+1 surprises, explicit control over every query, no entity lifecycle complexity.

### Query methods
```java
// Multiple rows
List<Map<String, Object>> rows = jdbc.queryForList("SELECT ...", args);

// Single row (throws EmptyResultDataAccessException if missing)
Map<String, Object> row = jdbc.queryForMap("SELECT ... WHERE id = ?", id);

// Single scalar
BigDecimal rate = jdbc.queryForObject("SELECT rate FROM ... WHERE ...", BigDecimal.class, args);
Long id = jdbc.queryForObject("INSERT ... RETURNING id", Long.class, args);

// INSERT / UPDATE / DELETE (returns rows affected)
int affected = jdbc.update("UPDATE ... WHERE ...", args);
```

### SQL style
Use text blocks. Indent SQL content 8 spaces from the method body. Put each JOIN and WHERE condition on its own line for readability.

```java
List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT
            c.user_debit_tx_id,
            c.from_amount,
            fc.code AS from_currency
        FROM digitaltwinapp.conversions c
        JOIN digitaltwinapp.currencies fc ON fc.id = c.from_currency_id
        WHERE c.user_debit_tx_id IS NOT NULL
        """);
```

### Type extraction from queryForList / queryForMap
JdbcTemplate returns raw `Object`. Cast via `Number` for numerics (never assume `Integer` vs `Long` vs `Double`), direct cast for `String` and `Boolean`.

```java
int    code    = ((Number) row.get("trans_code")).intValue();
long   txId    = ((Number) row.get("transaction_id")).longValue();
String email   = (String)  row.get("email");
boolean isFiat = Boolean.TRUE.equals(row.get("is_fiat"));   // null-safe
BigDecimal amt = toBigDecimal(row.get("amount"));           // helper handles null + type variance
```

---

## Schema migrations — Liquibase only

All schema changes go through Liquibase migrations in `src/main/resources/db/changelog/`. Never run `ALTER TABLE` manually in any environment.

- File naming: `NNN-description.xml` (zero-padded, sequential)
- Each migration has a rollback block
- Include in `db.changelog-master.xml` at the bottom
- Run automatically at startup — no manual step needed

---

## Startup initialization

### @PostConstruct — after Spring context, after Liquibase
Use `@PostConstruct` for initialization that needs the DB to be ready (schemas migrated, tables populated). Liquibase runs during `DataSource` initialization, which completes before any `@PostConstruct` method is called.

```java
@PostConstruct
public void load() {
    // DB tables are guaranteed to exist here
}
```

### ApplicationRunner — for one-time tasks at startup
Implement `ApplicationRunner` for tasks that should run after the full Spring context is ready. Always wrap in try/catch — a failed backfill or migration check must never crash the API.

```java
@Service
@Order(10)   // controls execution order among ApplicationRunners
public class MyStartupTask implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        try {
            doWork();
        } catch (Exception e) {
            log.error("Startup task failed — API is still operational: {}", e.getMessage(), e);
        }
    }
}
```

### @Scheduled — periodic background work
```java
@Scheduled(fixedDelay = 600_000)   // 10 minutes in ms — prefer fixedDelay over fixedRate to avoid overlap
public void refreshRates() { ... }
```

Enable scheduling with `@EnableScheduling` on the application or a `@Configuration` class.

---

## HTTP clients — RestClient (Spring 6.1)

Use `RestClient`, not `RestTemplate`. Create it per-component via `RestClient.create()` — no need for a shared bean.

```java
private final RestClient restClient = RestClient.create();

// GET
Map<String, Object> account = restClient.get()
        .uri(baseUrl + "/api/accounts/" + accountId)
        .retrieve()
        .body(Map.class);

// POST
Map<String, Object> response = restClient.post()
        .uri(baseUrl + "/api/transactions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(Map.class);
```

Always wrap in try/catch at the client layer. Return a sentinel value (`null`, `-1`, `List.of()`) so callers decide how to react. Do not let HTTP exceptions propagate raw through the service layer.

---

## External configuration

Use `@Value` for single properties. Group related properties in a record annotated with `@ConfigurationProperties` when there are several.

```java
@Value("${minicore.base-url}")
private String baseUrl;
```

Non-secret, environment-agnostic properties live in `application.yml`. Environment-specific secrets (DB credentials, API keys) live in `application-local.yml` (gitignored). Production non-secrets (CORS origins) must be in `application.yml`, not only in profile files — profile files only load when the profile is explicitly activated.

---

## Session auth

Spring Session JDBC persists sessions in PostgreSQL. The session attribute `"user"` holds a `UserInfo` record set by `AuthController` on successful Google OAuth.

```java
// Login
session.setAttribute("user", userInfo);

// Read in handler
UserInfo user = (UserInfo) session.getAttribute("user");

// Logout
session.invalidate();
```

No JWT. No Spring Security filter chain. See `api/TODO.md` for the Redis migration path when session load grows.

---

## Concurrent in-memory caches

### Atomic snapshot replacement (read-heavy, infrequent updates)
Use `volatile` + `Map.copyOf()`. Readers always see a fully-constructed map; the writer replaces the reference atomically.

```java
private volatile Map<Integer, JsonSchema> schemas = Map.of();

// Writer (called at startup and on refresh)
Map<Integer, JsonSchema> compiled = new HashMap<>();
// ... populate compiled ...
schemas = Map.copyOf(compiled);   // volatile write — atomic from readers' perspective

// Reader (no lock needed)
JsonSchema schema = schemas.get(transCode);
```

### Lazy per-key loading (write-once per key, concurrent reads after)
Use `ConcurrentHashMap.computeIfAbsent()`. Return `null` from the mapping function on transient failure — `ConcurrentHashMap` does not store null values, so the next call retries.

```java
private final ConcurrentHashMap<String, Map<Integer, DisplayTemplate>> cache =
        new ConcurrentHashMap<>();

Map<Integer, DisplayTemplate> templates = cache.computeIfAbsent(lang, l -> {
    try {
        return loadFromDb(l);           // returns Map (possibly empty) on success
    } catch (Exception e) {
        log.warn("Load failed for lang={}", l);
        return null;                    // not cached → next call retries
    }
});
```

An empty `Map.copyOf(emptyMap)` IS cached (correct: the key exists with no data). `null` is NOT cached (correct: transient failure, retry).

---

## CORS

Allowed origins are configured in `WebConfig` reading from `app.allowed-origins` (comma-separated). Non-secret production origins belong in `application.yml`; local overrides go in `application-local.yml`.

Never hardcode origins in Java — the set changes as new clients are added.
