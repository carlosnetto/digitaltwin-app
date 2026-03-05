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
 * Supports three operation types, detected from is_fiat flags:
 *
 * BUY  (fiat → crypto, e.g. USD → USDC):
 *   1. Debit  user's  fiat   account — 50005 Crypto Purchase Payment
 *   2. Credit user's  crypto account — 40003 Crypto Purchase
 *   3. Credit pool's  fiat   account — 10018 Internal Transfer In
 *   4. Debit  pool's  crypto account — 20021 Internal Transfer Out
 *
 * SELL (crypto → fiat, e.g. USDC → USD):
 *   1. Debit  user's  crypto account — 50003 Crypto Sale
 *   2. Credit user's  fiat   account — 40005 Crypto Sale Proceeds
 *   3. Credit pool's  crypto account — 10018 Internal Transfer In
 *   4. Debit  pool's  fiat   account — 20021 Internal Transfer Out
 *
 * CONVERT (crypto → crypto, e.g. USDC → USDT):
 *   1. Debit  user's  from-crypto account — 50002 Crypto Conversion Sent
 *   2. Credit user's  to-crypto   account — 40002 Crypto Conversion Received
 *   3. Credit pool's  from-crypto account — 10018 Internal Transfer In
 *   4. Debit  pool's  to-crypto   account — 20021 Internal Transfer Out
 *
 * CONVERT (fiat → fiat, e.g. BRL → USD):
 *   1. Debit  user's  from-fiat account — 50006 Currency Conversion Out
 *   2. Credit user's  to-fiat   account — 40006 Currency Conversion In
 *   3. Credit pool's  from-fiat account — 10018 Internal Transfer In
 *   4. Debit  pool's  to-fiat   account — 20021 Internal Transfer Out
 *
 * The pool is the Liquidity Buffer account (user_id = 1).
 */
@Service
public class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

    // User side — buy crypto (fiat → crypto)
    private static final int TX_BUY_FIAT_DEBIT        = 50005; // Crypto Purchase Payment (fiat goes out)
    private static final int TX_BUY_CRYPTO_CREDIT      = 40003; // Crypto Purchase         (crypto comes in)

    // User side — sell crypto (crypto → fiat)
    private static final int TX_SELL_CRYPTO_DEBIT      = 50003; // Crypto Sale             (crypto goes out)
    private static final int TX_SELL_FIAT_CREDIT       = 40005; // Crypto Sale Proceeds    (fiat comes in)

    // User side — convert crypto (crypto → crypto)
    private static final int TX_CONVERT_CRYPTO_DEBIT   = 50002; // Crypto Conversion Sent     (crypto goes out)
    private static final int TX_CONVERT_CRYPTO_CREDIT  = 40002; // Crypto Conversion Received (crypto comes in)

    // User side — convert fiat (fiat → fiat)
    private static final int TX_CONVERT_FIAT_DEBIT     = 50006; // Currency Conversion Out    (fiat goes out)
    private static final int TX_CONVERT_FIAT_CREDIT    = 40006; // Currency Conversion In     (fiat comes in)

    // Pool side (same regardless of direction)
    private static final int TX_CODE_POOL_CREDIT  = 10018; // Internal Transfer In
    private static final int TX_CODE_POOL_DEBIT   = 20021; // Internal Transfer Out

    private static final long POOL_USER_ID        = 1L;

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

        // ── 2. Resolve currency IDs + is_fiat ─────────────────────────────
        Map<String, Object> fromCcy = jdbc.queryForMap(
                "SELECT id, is_fiat FROM digitaltwinapp.currencies WHERE code = ?", fromCurrencyCode);
        Map<String, Object> toCcy = jdbc.queryForMap(
                "SELECT id, is_fiat FROM digitaltwinapp.currencies WHERE code = ?", toCurrencyCode);

        int fromCurrencyId = ((Number) fromCcy.get("id")).intValue();
        int toCurrencyId   = ((Number) toCcy.get("id")).intValue();

        // Determine operation type from is_fiat flags
        boolean fromIsFiat = Boolean.TRUE.equals(fromCcy.get("is_fiat"));
        boolean toIsFiat   = Boolean.TRUE.equals(toCcy.get("is_fiat"));

        int userDebitCode;
        int userCreditCode;
        if (fromIsFiat && toIsFiat) {
            // fiat → fiat conversion
            userDebitCode  = TX_CONVERT_FIAT_DEBIT;
            userCreditCode = TX_CONVERT_FIAT_CREDIT;
        } else if (!fromIsFiat && !toIsFiat) {
            // crypto → crypto conversion
            userDebitCode  = TX_CONVERT_CRYPTO_DEBIT;
            userCreditCode = TX_CONVERT_CRYPTO_CREDIT;
        } else if (fromIsFiat) {
            // fiat → crypto (buy)
            userDebitCode  = TX_BUY_FIAT_DEBIT;
            userCreditCode = TX_BUY_CRYPTO_CREDIT;
        } else {
            // crypto → fiat (sell)
            userDebitCode  = TX_SELL_CRYPTO_DEBIT;
            userCreditCode = TX_SELL_FIAT_CREDIT;
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
        long userDebitTxId  = miniCoreClient.createTransaction(userFromAccount, userDebitCode,        fromAmount, "DEBIT",  desc);
        long userCreditTxId = miniCoreClient.createTransaction(userToAccount,   userCreditCode,       toAmount,   "CREDIT", desc);
        long poolCreditTxId = miniCoreClient.createTransaction(poolFromAccount, TX_CODE_POOL_CREDIT,  fromAmount, "CREDIT", desc);
        long poolDebitTxId  = miniCoreClient.createTransaction(poolToAccount,   TX_CODE_POOL_DEBIT,   toAmount,   "DEBIT",  desc);

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
