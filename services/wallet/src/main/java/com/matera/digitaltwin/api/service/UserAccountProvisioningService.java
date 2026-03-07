package com.matera.digitaltwin.api.service;

import com.matera.digitaltwin.api.client.MiniCoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Provisions one mini-core account per currency for every user.
 *
 * Triggered two ways:
 *  1. Real-time: PostgresNotificationListener calls provisionUser() on INSERT to digitaltwinapp.users
 *  2. Catch-all: @Scheduled job retries any user/currency pairs not yet in user_accounts
 *     (handles mini-core downtime or missed notifications)
 *
 * Account number formula: user_id * 1000 + currency_id
 * A row in user_accounts is inserted only after mini-core confirms with HTTP 201.
 */
@Service
public class UserAccountProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountProvisioningService.class);

    private final JdbcTemplate jdbc;
    private final MiniCoreClient miniCoreClient;

    public UserAccountProvisioningService(JdbcTemplate jdbc, MiniCoreClient miniCoreClient) {
        this.jdbc = jdbc;
        this.miniCoreClient = miniCoreClient;
    }

    /**
     * Provision all missing accounts for a single user.
     * Called from the pg_notify listener on real-time insert.
     */
    public void provisionUser(long userId) {
        List<Map<String, Object>> missing = findMissingCurrencies(userId);
        if (missing.isEmpty()) {
            return;
        }
        log.info("Provisioning {} mini-core account(s) for user_id={}", missing.size(), userId);
        for (Map<String, Object> row : missing) {
            provision(userId, row);
        }
    }

    /**
     * Periodic catch-all: retries any user+currency not yet in user_accounts.
     * Runs every 60 seconds. Handles mini-core downtime gracefully.
     */
    @Scheduled(fixedDelayString = "60000")
    public void retryMissingAccounts() {
        List<Map<String, Object>> missing = findAllMissingCurrencies();
        if (missing.isEmpty()) {
            return;
        }
        log.info("Retry catch-all: provisioning {} missing account(s)", missing.size());
        for (Map<String, Object> row : missing) {
            long userId = ((Number) row.get("user_id")).longValue();
            provision(userId, row);
        }
    }

    private static final int TX_CASH_DEPOSIT = 10001;
    private static final BigDecimal WELCOME_BRL_AMOUNT = new BigDecimal("10000.00");

    private void provision(long userId, Map<String, Object> currencyRow) {
        int currencyId   = ((Number) currencyRow.get("id")).intValue();
        String code      = (String) currencyRow.get("code");
        String accountNumber = String.valueOf(userId * 1000L + currencyId);

        long minicoreAccountId = miniCoreClient.createAccount(accountNumber, code);
        if (minicoreAccountId < 0) {
            log.warn("Skipping user_accounts insert — mini-core call failed for user_id={} currency={}", userId, code);
            return;
        }

        jdbc.update("""
                INSERT INTO digitaltwinapp.user_accounts
                    (user_id, currency_id, account_number, minicore_account_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, currency_id) DO NOTHING
                """,
                userId, currencyId, accountNumber, minicoreAccountId);

        log.info("Provisioned account {} ({}) for user_id={} → minicore_account_id={}",
                accountNumber, code, userId, minicoreAccountId);

        if ("BRL".equals(code)) {
            long txId = miniCoreClient.createTransaction(
                    minicoreAccountId, TX_CASH_DEPOSIT, WELCOME_BRL_AMOUNT, "CREDIT", null);
            if (txId >= 0) {
                log.info("Welcome credit of {} BRL posted for user_id={} → tx_id={}", WELCOME_BRL_AMOUNT, userId, txId);
            } else {
                log.warn("Welcome credit failed for user_id={} account={}", userId, minicoreAccountId);
            }
        }
    }

    /** Currencies not yet provisioned for a specific user. */
    private List<Map<String, Object>> findMissingCurrencies(long userId) {
        return jdbc.queryForList("""
                SELECT c.id, c.code
                FROM digitaltwinapp.currencies c
                WHERE NOT EXISTS (
                    SELECT 1 FROM digitaltwinapp.user_accounts ua
                    WHERE ua.user_id = ? AND ua.currency_id = c.id
                )
                """, userId);
    }

    /** All user+currency pairs not yet provisioned, across all users. */
    private List<Map<String, Object>> findAllMissingCurrencies() {
        return jdbc.queryForList("""
                SELECT u.user_id, c.id, c.code
                FROM digitaltwinapp.users u
                CROSS JOIN digitaltwinapp.currencies c
                WHERE u.status = 'active'
                  AND NOT EXISTS (
                      SELECT 1 FROM digitaltwinapp.user_accounts ua
                      WHERE ua.user_id = u.user_id AND ua.currency_id = c.id
                  )
                """);
    }
}
