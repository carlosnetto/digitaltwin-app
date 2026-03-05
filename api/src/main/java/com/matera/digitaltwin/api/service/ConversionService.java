package com.matera.digitaltwin.api.service;

import com.matera.digitaltwin.api.client.MiniCoreClient;
import com.matera.digitaltwin.api.model.ConversionResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Executes a currency conversion between two of the user's wallets.
 *
 * Flow (buy USDC with USD example):
 *   1. Debit  user's  fromCurrency account  (user pays)
 *   2. Credit user's  toCurrency   account  (user receives)
 *   3. Credit pool's  fromCurrency account  (pool receives payment)
 *   4. Debit  pool's  toCurrency   account  (pool delivers crypto)
 *
 * Transaction codes used:
 *   DEBIT  → 20026 (P2P Sent)
 *   CREDIT → 10027 (P2P Received)
 *
 * The pool is the Liquidity Buffer account (user_id = 1).
 */
@Service
public class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

    private static final int TX_CODE_DEBIT  = 20026; // P2P Sent
    private static final int TX_CODE_CREDIT = 10027; // P2P Received
    private static final long POOL_USER_ID  = 1L;

    private final JdbcTemplate    jdbc;
    private final MiniCoreClient  miniCoreClient;

    public ConversionService(JdbcTemplate jdbc, MiniCoreClient miniCoreClient) {
        this.jdbc           = jdbc;
        this.miniCoreClient = miniCoreClient;
    }

    public ConversionResultDto convert(String email,
                                       String fromCurrencyCode,
                                       String toCurrencyCode,
                                       double fromAmount) {

        // ── 1. Resolve user_id ─────────────────────────────────────────────
        Long userId = jdbc.queryForObject(
                "SELECT user_id FROM digitaltwinapp.users WHERE email = ?",
                Long.class, email);
        if (userId == null) throw new IllegalArgumentException("User not found: " + email);

        // ── 2. Resolve currency IDs ────────────────────────────────────────
        Integer fromCurrencyId = jdbc.queryForObject(
                "SELECT id FROM digitaltwinapp.currencies WHERE code = ?",
                Integer.class, fromCurrencyCode);
        Integer toCurrencyId = jdbc.queryForObject(
                "SELECT id FROM digitaltwinapp.currencies WHERE code = ?",
                Integer.class, toCurrencyCode);
        if (fromCurrencyId == null || toCurrencyId == null) {
            throw new IllegalArgumentException("Unknown currency pair: " + fromCurrencyCode + "/" + toCurrencyCode);
        }

        // ── 3. Look up exchange rate ───────────────────────────────────────
        BigDecimal rate = jdbc.queryForObject(
                "SELECT rate FROM digitaltwinapp.exchange_rates WHERE from_currency_id = ? AND to_currency_id = ?",
                BigDecimal.class, fromCurrencyId, toCurrencyId);
        if (rate == null) {
            throw new IllegalStateException("No exchange rate for " + fromCurrencyCode + " → " + toCurrencyCode);
        }

        BigDecimal fromAmountBD = BigDecimal.valueOf(fromAmount).setScale(8, RoundingMode.HALF_UP);
        BigDecimal toAmountBD   = fromAmountBD.multiply(rate).setScale(8, RoundingMode.HALF_UP);
        double toAmount = toAmountBD.doubleValue();

        // ── 4. Resolve mini-core account IDs ──────────────────────────────
        long userFromAccount = getMiniCoreAccountId(userId, fromCurrencyId);
        long userToAccount   = getMiniCoreAccountId(userId, toCurrencyId);
        long poolFromAccount = getMiniCoreAccountId(POOL_USER_ID, fromCurrencyId);
        long poolToAccount   = getMiniCoreAccountId(POOL_USER_ID, toCurrencyId);

        String desc = "Currency conversion " + fromCurrencyCode + " → " + toCurrencyCode;

        // ── 5. Execute the 4 mini-core transactions ────────────────────────
        long userDebitTxId  = miniCoreClient.createTransaction(userFromAccount, TX_CODE_DEBIT,  fromAmount, "DEBIT",  desc);
        long userCreditTxId = miniCoreClient.createTransaction(userToAccount,   TX_CODE_CREDIT, toAmount,   "CREDIT", desc);
        long poolCreditTxId = miniCoreClient.createTransaction(poolFromAccount, TX_CODE_CREDIT, fromAmount, "CREDIT", desc);
        long poolDebitTxId  = miniCoreClient.createTransaction(poolToAccount,   TX_CODE_DEBIT,  toAmount,   "DEBIT",  desc);

        if (userDebitTxId < 0 || userCreditTxId < 0) {
            log.error("Conversion failed: user transactions returned error (debit={}, credit={})",
                    userDebitTxId, userCreditTxId);
            throw new RuntimeException("mini-core transaction failed — conversion aborted");
        }
        if (poolCreditTxId < 0 || poolDebitTxId < 0) {
            log.warn("Pool transactions may have failed (credit={}, debit={}); user transactions succeeded",
                    poolCreditTxId, poolDebitTxId);
        }

        // ── 6. Record the conversion ───────────────────────────────────────
        Long conversionId = jdbc.queryForObject("""
                INSERT INTO digitaltwinapp.conversions
                    (user_id, from_currency_id, to_currency_id,
                     from_amount, to_amount, rate,
                     user_debit_tx_id, user_credit_tx_id, pool_credit_tx_id, pool_debit_tx_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId, fromCurrencyId, toCurrencyId,
                fromAmountBD, toAmountBD, rate,
                userDebitTxId > 0 ? userDebitTxId : null,
                userCreditTxId > 0 ? userCreditTxId : null,
                poolCreditTxId > 0 ? poolCreditTxId : null,
                poolDebitTxId > 0 ? poolDebitTxId : null);

        log.info("Conversion #{} complete: {} {} → {} {} (rate={}) user={}",
                conversionId, fromAmount, fromCurrencyCode, toAmount, toCurrencyCode, rate, email);

        return new ConversionResultDto(
                conversionId == null ? -1 : conversionId,
                fromCurrencyCode, toCurrencyCode,
                fromAmount, toAmount, rate.doubleValue());
    }

    private long getMiniCoreAccountId(long userId, int currencyId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT minicore_account_id FROM digitaltwinapp.user_accounts WHERE user_id = ? AND currency_id = ?",
                userId, currencyId);
        return ((Number) row.get("minicore_account_id")).longValue();
    }
}
