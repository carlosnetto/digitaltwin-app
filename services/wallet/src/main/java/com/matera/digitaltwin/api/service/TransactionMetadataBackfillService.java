package com.matera.digitaltwin.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import com.matera.digitaltwin.api.client.MiniCoreClient;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * TEMPORARY — ONE-TIME HISTORICAL BACKFILL. REMOVE AFTER ROLLOUT IS COMPLETE.
 *
 * Populates digitaltwinapp.transaction_metadata for all transactions that
 * already exist in mini-core before the metadata capture layer was introduced.
 *
 * Safe to leave running indefinitely — every insert uses
 * ON CONFLICT (ledger_id) DO NOTHING so repeated startups are a no-op for
 * rows already written. Cost on a warm system is one SELECT per user account
 * against mini-core plus fast PK lookups.
 *
 * HOW TO KNOW IT IS DONE:
 *   SELECT COUNT(*) FROM digitaltwinapp.transaction_metadata;
 *   SELECT COUNT(*) FROM digitaltwinapp.p2p_transactions;   -- expect 2× rows
 *   SELECT COUNT(*) FROM digitaltwinapp.conversions;        -- expect 2× rows
 *   (each p2p and each conversion produces two metadata rows: one per ledger tx)
 *
 * REMOVE THIS CLASS once all environments show the counts match and the backfill
 * log line reports 0 new rows on startup.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Order(10)
public class TransactionMetadataBackfillService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TransactionMetadataBackfillService.class);

    private static final Set<Integer> P2P_CODES = Set.of(20026, 10027);

    private final JdbcTemplate  jdbc;
    private final MiniCoreClient miniCoreClient;
    private final ObjectMapper   objectMapper;

    public TransactionMetadataBackfillService(JdbcTemplate jdbc,
                                              MiniCoreClient miniCoreClient,
                                              ObjectMapper objectMapper) {
        this.jdbc           = jdbc;
        this.miniCoreClient = miniCoreClient;
        this.objectMapper   = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("BACKFILL [transaction_metadata]: starting historical backfill...");
        try {
            int conversions = backfillConversions();
            int p2p         = backfillP2p();
            log.info("BACKFILL [transaction_metadata]: done — {} conversion rows, {} p2p rows inserted",
                    conversions, p2p);
        } catch (Exception e) {
            // Never crash the API startup. The backfill will retry on the next restart.
            log.error("BACKFILL [transaction_metadata]: failed — API is still operational, " +
                      "metadata for historical transactions may be incomplete. " +
                      "Will retry on next startup. Error: {}", e.getMessage(), e);
        }
    }

    // ── Conversions ──────────────────────────────────────────────────────────

    /**
     * Reads all rows from digitaltwinapp.conversions, determines the correct
     * transaction code for each side (debit/credit) from the currency is_fiat
     * flags — mirroring the same logic in ConversionService — and inserts one
     * metadata row per user-facing ledger transaction.
     */
    private int backfillConversions() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    c.user_debit_tx_id,
                    c.user_credit_tx_id,
                    c.from_amount,
                    c.to_amount,
                    c.rate,
                    fc.code    AS from_currency,
                    tc.code    AS to_currency,
                    fc.is_fiat AS from_is_fiat,
                    tc.is_fiat AS to_is_fiat
                FROM digitaltwinapp.conversions c
                JOIN digitaltwinapp.currencies fc ON fc.id = c.from_currency_id
                JOIN digitaltwinapp.currencies tc ON tc.id = c.to_currency_id
                WHERE c.user_debit_tx_id  IS NOT NULL
                  AND c.user_credit_tx_id IS NOT NULL
                """);

        int inserted = 0;
        for (var row : rows) {
            long       debitTxId    = toLong(row.get("user_debit_tx_id"));
            long       creditTxId   = toLong(row.get("user_credit_tx_id"));
            String     fromCurrency = (String) row.get("from_currency");
            String     toCurrency   = (String) row.get("to_currency");
            BigDecimal fromAmt      = toBigDecimal(row.get("from_amount"));
            BigDecimal toAmt        = toBigDecimal(row.get("to_amount"));
            BigDecimal rate         = toBigDecimal(row.get("rate"));
            boolean    fromIsFiat   = Boolean.TRUE.equals(row.get("from_is_fiat"));
            boolean    toIsFiat     = Boolean.TRUE.equals(row.get("to_is_fiat"));

            // Mirror ConversionService transaction-code selection logic
            int debitCode;
            int creditCode;
            if (fromIsFiat && toIsFiat) {
                debitCode  = 50006; creditCode = 40006;  // fiat → fiat
            } else if (!fromIsFiat && !toIsFiat) {
                debitCode  = 50002; creditCode = 40002;  // crypto → crypto
            } else if (fromIsFiat) {
                debitCode  = 50005; creditCode = 40003;  // buy  (fiat → crypto)
            } else {
                debitCode  = 50003; creditCode = 40005;  // sell (crypto → fiat)
            }

            // Both sides of a conversion share the same metadata blob
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("from_amount",   fromAmt);
            meta.put("from_currency", fromCurrency);
            meta.put("to_amount",     toAmt);
            meta.put("to_currency",   toCurrency);
            meta.put("rate",          rate);

            inserted += insertMetadata(debitTxId,  debitCode,  meta);
            inserted += insertMetadata(creditTxId, creditCode, meta);
        }

        log.info("BACKFILL [conversions]: processed {} records → {} rows inserted",
                rows.size(), inserted);
        return inserted;
    }

    // ── P2P ──────────────────────────────────────────────────────────────────

    /**
     * Builds a reverse-lookup map (minicore transaction_id → owner info) by
     * fetching every P2P transaction for every registered user account, then
     * uses that map to enrich p2p_transactions rows with sender/recipient
     * names and emails.
     */
    private int backfillP2p() {
        Map<Long, TxOwner> txOwners = buildP2pTxOwnerMap();
        if (txOwners.isEmpty()) {
            log.info("BACKFILL [p2p]: mini-core returned no P2P transactions — skipping");
            return 0;
        }

        List<Map<String, Object>> p2pRows = jdbc.queryForList("""
                SELECT amount, debit_tx_id, credit_tx_id
                FROM digitaltwinapp.p2p_transactions
                """);

        int inserted = 0;
        for (var row : p2pRows) {
            long       debitTxId  = toLong(row.get("debit_tx_id"));
            long       creditTxId = toLong(row.get("credit_tx_id"));
            BigDecimal amount     = toBigDecimal(row.get("amount"));

            TxOwner sender    = txOwners.get(debitTxId);
            TxOwner recipient = txOwners.get(creditTxId);

            if (sender == null || recipient == null) {
                log.warn("BACKFILL [p2p]: cannot resolve owner for debit_tx={} credit_tx={} — skipping",
                        debitTxId, creditTxId);
                continue;
            }

            // 20026 P2P Sent — from the sender's perspective
            Map<String, Object> sentMeta = new LinkedHashMap<>();
            sentMeta.put("recipient_email", recipient.email());
            sentMeta.put("recipient_name",  recipient.name());
            sentMeta.put("amount",          amount);
            sentMeta.put("currency",        sender.currencyCode());

            // 10027 P2P Received — from the recipient's perspective
            Map<String, Object> receivedMeta = new LinkedHashMap<>();
            receivedMeta.put("sender_email", sender.email());
            receivedMeta.put("sender_name",  sender.name());
            receivedMeta.put("amount",       amount);
            receivedMeta.put("currency",     recipient.currencyCode());

            inserted += insertMetadata(debitTxId,  20026, sentMeta);
            inserted += insertMetadata(creditTxId, 10027, receivedMeta);
        }

        log.info("BACKFILL [p2p]: processed {} records → {} rows inserted",
                p2pRows.size(), inserted);
        return inserted;
    }

    /**
     * Fetches transactions from mini-core for every user account and returns
     * a map of { minicore_transaction_id → TxOwner } restricted to P2P codes.
     * Keeping only P2P codes avoids loading the full transaction history for
     * every account into memory.
     */
    private Map<Long, TxOwner> buildP2pTxOwnerMap() {
        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT ua.minicore_account_id,
                       u.email,
                       u.name,
                       c.code AS currency_code
                FROM digitaltwinapp.user_accounts ua
                JOIN digitaltwinapp.users      u ON u.user_id = ua.user_id
                JOIN digitaltwinapp.currencies c ON c.id      = ua.currency_id
                WHERE ua.user_id != 1
                """);

        Map<Long, TxOwner> map = new HashMap<>();
        for (var account : accounts) {
            long   accountId    = toLong(account.get("minicore_account_id"));
            String email        = (String) account.get("email");
            String name         = (String) account.get("name");
            String currencyCode = (String) account.get("currency_code");

            List<Map<String, Object>> txs = miniCoreClient.getTransactions(accountId);
            for (var tx : txs) {
                int txCode = ((Number) tx.get("transaction_code")).intValue();
                if (P2P_CODES.contains(txCode)) {
                    long txId = toLong(tx.get("transaction_id"));
                    map.put(txId, new TxOwner(email, name, currencyCode));
                }
            }
        }
        return map;
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    private int insertMetadata(long ledgerId, int transCode, Map<String, Object> metadata) {
        try {
            // schema_version is placed FIRST so the blob is self-contained.
            // If this payload is forwarded to any ledger that stores JSON per transaction,
            // the reader can identify the exact schema and i18n display templates that apply
            // without access to our transaction_schemas / transaction_schema_i18n tables —
            // even if retrieved years later on a completely different system.
            Map<String, Object> enriched = new LinkedHashMap<>();
            enriched.put("schema_version", 1);
            enriched.putAll(metadata);

            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(objectMapper.writeValueAsString(enriched));

            return jdbc.update("""
                    INSERT INTO digitaltwinapp.transaction_metadata
                        (id, trans_code, ledger_id, schema_version, metadata)
                    VALUES (?, ?, ?, 1, ?)
                    ON CONFLICT (ledger_id) WHERE ledger_id IS NOT NULL DO NOTHING
                    """,
                    UuidCreator.getTimeOrderedEpoch(),
                    transCode,
                    String.valueOf(ledgerId),
                    jsonb);

        } catch (Exception e) {
            log.warn("BACKFILL: failed to insert metadata for ledger_id={} trans_code={}: {}",
                    ledgerId, transCode, e.getMessage());
            return 0;
        }
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        return new BigDecimal(value.toString());
    }

    private record TxOwner(String email, String name, String currencyCode) {}
}
