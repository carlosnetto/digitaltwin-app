package com.matera.digitaltwin.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matera.digitaltwin.api.client.MiniCoreClient;
import com.matera.digitaltwin.api.model.WalletDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Returns the logged-in user's wallets with live balances from mini-core.
 */
@Service
public class WalletService {

    private static final Logger   log       = LoggerFactory.getLogger(WalletService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate               jdbc;
    private final MiniCoreClient             miniCoreClient;
    private final TransactionDisplayService  displayService;
    private final ObjectMapper               objectMapper;

    // ledger_id → cached metadata entry with rolling TTL
    private final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> metadataCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public WalletService(JdbcTemplate jdbc,
                         MiniCoreClient miniCoreClient,
                         TransactionDisplayService displayService,
                         ObjectMapper objectMapper) {
        this.jdbc           = jdbc;
        this.miniCoreClient = miniCoreClient;
        this.displayService = displayService;
        this.objectMapper   = objectMapper;
    }

    public List<WalletDto> getWalletsForUser(String email) {
        Long userId;
        try {
            userId = jdbc.queryForObject(
                    "SELECT user_id FROM digitaltwinapp.users WHERE email = ?",
                    Long.class, email);
        } catch (EmptyResultDataAccessException e) {
            log.warn("getWallets: no user found for email={}", email);
            return List.of();
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ua.minicore_account_id,
                       ua.account_number,
                       c.id             AS currency_id,
                       c.code,
                       c.name,
                       c.is_fiat,
                       c.logo_url,
                       c.decimal_places
                FROM digitaltwinapp.user_accounts ua
                JOIN digitaltwinapp.currencies c ON c.id = ua.currency_id
                WHERE ua.user_id = ?
                ORDER BY c.id
                """, userId);

        return rows.stream().map(this::toDto).toList();
    }

    /**
     * Returns up to 50 recent transactions for the user's account in the given currency,
     * enriched with a resolved i18n summary string wherever transaction_metadata exists.
     *
     * The metadata lookup is a single batch query (WHERE ledger_id IN (...)) so latency
     * does not grow with the number of transactions.
     *
     * @param lang BCP-47 language tag (e.g. "en", "pt-BR"); falls back to "en" if no
     *             template exists for the requested language.
     */
    public List<Map<String, Object>> getTransactions(String email, String currencyCode, String lang) {
        Long userId;
        try {
            userId = jdbc.queryForObject(
                    "SELECT user_id FROM digitaltwinapp.users WHERE email = ?", Long.class, email);
        } catch (Exception e) {
            return List.of();
        }

        Long minicoreAccountId;
        try {
            minicoreAccountId = jdbc.queryForObject("""
                    SELECT ua.minicore_account_id
                    FROM digitaltwinapp.user_accounts ua
                    JOIN digitaltwinapp.currencies c ON c.id = ua.currency_id
                    WHERE ua.user_id = ? AND c.code = ?
                    """, Long.class, userId, currencyCode);
        } catch (Exception e) {
            return List.of();
        }
        if (minicoreAccountId == null) return List.of();

        // ── Fetch and cap ─────────────────────────────────────────────────────
        List<Map<String, Object>> capped = miniCoreClient.getTransactions(minicoreAccountId).stream()
                .sorted(Comparator.comparingLong(tx -> -((Number) tx.get("transaction_id")).longValue()))
                .limit(50)
                .toList();

        // ── Batch-fetch summaries for all IDs in one query ────────────────────
        Map<String, String> summaryByLedgerId = fetchSummaries(capped, lang);

        // ── Build the response: format description + attach summary ───────────
        return capped.stream().map(tx -> {
            Map<String, Object> copy = new HashMap<>(tx);

            String raw = (String) tx.get("transaction_description");
            if (raw != null && !raw.isEmpty()) {
                copy.put("transaction_description",
                        Character.toUpperCase(raw.charAt(0)) + raw.substring(1).toLowerCase());
            }

            String ledgerId = String.valueOf(((Number) tx.get("transaction_id")).longValue());
            String summary = summaryByLedgerId.get(ledgerId);
            if (summary != null) {
                copy.put("summary", summary);
            }

            return (Map<String, Object>) copy;
        }).toList();
    }

    /**
     * Returns the full resolved display (summary + labeled fields) for a single transaction.
     * Intended for the tap-to-detail use case — one SQL query at most.
     *
     * If the transaction was already fetched as part of a recent list call it will be in the
     * shared metadata cache and no DB round-trip is needed at all. This means a user who
     * scrolls the list and then taps a row typically gets the detail with zero extra queries.
     *
     * @param ledgerId mini-core transaction_id as a string
     * @param lang     BCP-47 language tag
     * @return resolved display, or Optional.empty() if no metadata row exists for this transaction
     */
    public Optional<TransactionDisplayService.ResolvedDisplay> getTransactionDetail(String ledgerId, String lang) {
        // ── Cache hit ─────────────────────────────────────────────────────────
        CacheEntry cached = metadataCache.get(ledgerId);
        if (cached != null && !cached.isExpired()) {
            return displayService.resolve(cached.transCode(), lang, cached.metadata());
        }

        // ── Cache miss — single DB fetch ──────────────────────────────────────
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT trans_code, metadata::text AS metadata
                    FROM digitaltwinapp.transaction_metadata
                    WHERE ledger_id = ?
                    """, ledgerId);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();   // no metadata — caller shows raw ledger data
        } catch (Exception e) {
            log.warn("getTransactionDetail: query failed for ledger_id={}: {}", ledgerId, e.getMessage());
            return Optional.empty();
        }

        try {
            int transCode = ((Number) row.get("trans_code")).intValue();
            Map<String, Object> metadata = objectMapper.readValue(
                    (String) row.get("metadata"), new TypeReference<>() {});
            metadataCache.put(ledgerId, new CacheEntry(transCode, metadata, Instant.now().plus(CACHE_TTL)));
            return displayService.resolve(transCode, lang, metadata);
        } catch (Exception e) {
            log.warn("getTransactionDetail: resolve failed for ledger_id={}: {}", ledgerId, e.getMessage());
            return Optional.empty();
        }
    }

    public String lookupUserName(String email) {
        try {
            return jdbc.queryForObject(
                    "SELECT name FROM digitaltwinapp.users WHERE email = ? AND status = 'active'",
                    String.class, email);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Map<String, Object> p2pTransfer(String senderEmail, String recipientEmail,
                                           String currencyCode, double amount) {
        if (senderEmail.equalsIgnoreCase(recipientEmail)) {
            throw new IllegalArgumentException("Cannot send to yourself");
        }

        int decimalPlaces = jdbc.queryForObject(
                "SELECT decimal_places FROM digitaltwinapp.currencies WHERE code = ?",
                Integer.class, currencyCode);
        BigDecimal amountBD = BigDecimal.valueOf(amount).setScale(decimalPlaces, java.math.RoundingMode.DOWN);

        Long senderUserId = jdbc.queryForObject(
                "SELECT user_id FROM digitaltwinapp.users WHERE email = ?", Long.class, senderEmail);

        Long senderAccountId = jdbc.queryForObject("""
                SELECT ua.minicore_account_id
                FROM digitaltwinapp.user_accounts ua
                JOIN digitaltwinapp.currencies c ON c.id = ua.currency_id
                WHERE ua.user_id = ? AND c.code = ?
                """, Long.class, senderUserId, currencyCode);

        Long recipientUserId;
        try {
            recipientUserId = jdbc.queryForObject(
                    "SELECT user_id FROM digitaltwinapp.users WHERE email = ? AND status = 'active'",
                    Long.class, recipientEmail);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipient not found");
        }

        Long recipientAccountId;
        try {
            recipientAccountId = jdbc.queryForObject("""
                    SELECT ua.minicore_account_id
                    FROM digitaltwinapp.user_accounts ua
                    JOIN digitaltwinapp.currencies c ON c.id = ua.currency_id
                    WHERE ua.user_id = ? AND c.code = ?
                    """, Long.class, recipientUserId, currencyCode);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipient has no " + currencyCode + " account");
        }

        Map<String, Object> senderAccount = miniCoreClient.getAccount(senderAccountId);
        if (senderAccount == null) throw new IllegalStateException("Could not fetch sender balance");
        double balance = ((Number) senderAccount.get("available_balance")).doubleValue();
        if (balance < amountBD.doubleValue()) throw new IllegalArgumentException("Insufficient balance");

        long debitTxId  = miniCoreClient.createTransaction(senderAccountId,    20026, amountBD, "DEBIT",  recipientEmail);
        long creditTxId = miniCoreClient.createTransaction(recipientAccountId, 10027, amountBD, "CREDIT", senderEmail);

        if (debitTxId < 0 || creditTxId < 0) {
            throw new IllegalStateException("Transfer failed — mini-core error");
        }

        long p2pId = jdbc.queryForObject("""
                INSERT INTO digitaltwinapp.p2p_transactions (amount, debit_tx_id, credit_tx_id)
                VALUES (?, ?, ?)
                RETURNING id
                """, Long.class, amountBD, debitTxId, creditTxId);

        log.info("P2P transfer #{}: {} {} debit_tx={} credit_tx={}",
                p2pId, amountBD, currencyCode, debitTxId, creditTxId);

        return Map.of("p2pId", p2pId, "debitTxId", debitTxId, "creditTxId", creditTxId);
    }

    public BigDecimal getRate(String fromCode, String toCode) {
        return jdbc.queryForObject("""
                SELECT er.rate
                FROM digitaltwinapp.exchange_rates er
                JOIN digitaltwinapp.currencies f ON f.id = er.from_currency_id
                JOIN digitaltwinapp.currencies t ON t.id = er.to_currency_id
                WHERE f.code = ? AND t.code = ?
                """, BigDecimal.class, fromCode, toCode);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a summary string for every transaction in the list.
     *
     * Strategy: partition the ledger IDs into cache-hits and cache-misses. Fetch only
     * the misses in a single IN query, store them in the shared cache, then resolve all
     * summaries in memory. On a repeat call within the TTL window (e.g. the user slides
     * back to this wallet) the DB query is skipped entirely.
     *
     * Cache entries expire individually after CACHE_TTL. Lazy eviction — expired entries
     * are overwritten on the next miss cycle, never held in memory forever.
     */
    private Map<String, String> fetchSummaries(List<Map<String, Object>> txs, String lang) {
        if (txs.isEmpty()) return Map.of();

        List<String> ledgerIds = txs.stream()
                .map(tx -> String.valueOf(((Number) tx.get("transaction_id")).longValue()))
                .toList();

        // ── Partition: cache hits vs misses ───────────────────────────────────
        List<String> missing = ledgerIds.stream()
                .filter(id -> { CacheEntry e = metadataCache.get(id); return e == null || e.isExpired(); })
                .toList();

        // ── Fetch only missing entries from the DB in a single batch ──────────
        if (!missing.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(missing.size(), "?"));
            Instant expiresAt = Instant.now().plus(CACHE_TTL);
            try {
                jdbc.queryForList(
                        "SELECT trans_code, ledger_id, metadata::text AS metadata " +
                        "FROM digitaltwinapp.transaction_metadata " +
                        "WHERE ledger_id IN (" + placeholders + ")",
                        missing.toArray())
                    .forEach(row -> {
                        String ledgerId = (String) row.get("ledger_id");
                        int transCode   = ((Number) row.get("trans_code")).intValue();
                        try {
                            Map<String, Object> metadata = objectMapper.readValue(
                                    (String) row.get("metadata"), new TypeReference<>() {});
                            metadataCache.put(ledgerId, new CacheEntry(transCode, metadata, expiresAt));
                        } catch (Exception e) {
                            log.warn("fetchSummaries: skipping ledger_id={}: {}", ledgerId, e.getMessage());
                        }
                    });
                log.debug("fetchSummaries: cache={} db={} total={}",
                        ledgerIds.size() - missing.size(), missing.size(), ledgerIds.size());
            } catch (Exception e) {
                log.warn("fetchSummaries: DB batch query failed for {} entries: {}", missing.size(), e.getMessage());
            }
        }

        // ── Resolve summaries for all IDs from cache (hits + newly fetched) ───
        Map<String, String> result = new HashMap<>();
        for (String ledgerId : ledgerIds) {
            CacheEntry entry = metadataCache.get(ledgerId);
            if (entry != null && !entry.isExpired()) {
                displayService.resolve(entry.transCode(), lang, entry.metadata())
                        .ifPresent(display -> result.put(ledgerId, display.summary()));
            }
        }
        return result;
    }

    private WalletDto toDto(Map<String, Object> row) {
        long minicoreAccountId = ((Number) row.get("minicore_account_id")).longValue();
        double balance = fetchBalance(minicoreAccountId);

        return new WalletDto(
                String.valueOf(((Number) row.get("currency_id")).intValue()),
                (String) row.get("code"),
                (String) row.get("name"),
                Boolean.TRUE.equals(row.get("is_fiat")),
                (String) row.get("logo_url"),
                balance,
                (String) row.get("account_number"),
                minicoreAccountId,
                ((Number) row.get("decimal_places")).intValue()
        );
    }

    private double fetchBalance(long accountId) {
        Map<String, Object> account = miniCoreClient.getAccount(accountId);
        if (account == null) return 0.0;
        Object bal = account.get("available_balance");
        return bal == null ? 0.0 : ((Number) bal).doubleValue();
    }

    // ── Cache types ───────────────────────────────────────────────────────────

    /**
     * A single transaction's deserialized metadata, held in memory until the TTL expires.
     * Storing the raw metadata map (rather than the resolved string) keeps the cache
     * language-agnostic — summary resolution happens at serve time from the in-memory map
     * and is O(n) string concatenation with no I/O.
     */
    private record CacheEntry(int transCode, Map<String, Object> metadata, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }
}
