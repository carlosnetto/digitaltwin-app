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
