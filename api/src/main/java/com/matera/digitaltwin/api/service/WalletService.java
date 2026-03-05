package com.matera.digitaltwin.api.service;

import com.matera.digitaltwin.api.client.MiniCoreClient;
import com.matera.digitaltwin.api.model.WalletDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Returns the logged-in user's wallets with live balances from mini-core.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final JdbcTemplate jdbc;
    private final MiniCoreClient miniCoreClient;

    public WalletService(JdbcTemplate jdbc, MiniCoreClient miniCoreClient) {
        this.jdbc = jdbc;
        this.miniCoreClient = miniCoreClient;
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
                       c.id        AS currency_id,
                       c.code,
                       c.name,
                       c.is_fiat,
                       c.logo_url
                FROM digitaltwinapp.user_accounts ua
                JOIN digitaltwinapp.currencies c ON c.id = ua.currency_id
                WHERE ua.user_id = ?
                ORDER BY c.id
                """, userId);

        return rows.stream().map(this::toDto).toList();
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
                minicoreAccountId
        );
    }

    private double fetchBalance(long accountId) {
        Map<String, Object> account = miniCoreClient.getAccount(accountId);
        if (account == null) return 0.0;
        Object bal = account.get("available_balance");
        return bal == null ? 0.0 : ((Number) bal).doubleValue();
    }
}
