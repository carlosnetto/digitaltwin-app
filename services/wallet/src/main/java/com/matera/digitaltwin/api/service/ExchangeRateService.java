package com.matera.digitaltwin.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Refreshes exchange rates every 10 minutes from open.er-api.com (free, no key required).
 *
 * Only non-stablecoin pairs are updated — stablecoin pairs (USDC↔USD, USDT↔USD, USDC↔USDT)
 * are seeded with rate=1.0 and never touched by this service.
 *
 * Strategy: for each distinct from_currency in non-stablecoin pairs, fetch all rates
 * in one API call, then update all matching rows in exchange_rates.
 *
 * API: GET https://open.er-api.com/v6/latest/{CODE}
 * Returns: { "rates": { "BRL": 5.75, "USD": 1.0, ... } }
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);
    private static final String API_URL = "https://open.er-api.com/v6/latest/";

    private final JdbcTemplate jdbc;
    private final RestClient restClient = RestClient.create();

    public ExchangeRateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "600000", initialDelayString = "10000")
    public void refreshRates() {
        // Find all distinct from-currencies that have at least one non-stablecoin pair
        List<Map<String, Object>> fromCurrencies = jdbc.queryForList("""
                SELECT DISTINCT c.id, c.code
                FROM digitaltwinapp.exchange_rates er
                JOIN digitaltwinapp.currencies c ON c.id = er.from_currency_id
                WHERE er.is_stablecoin_pair = false
                ORDER BY c.code
                """);

        for (Map<String, Object> row : fromCurrencies) {
            int fromId  = ((Number) row.get("id")).intValue();
            String code = (String) row.get("code");
            fetchAndUpdate(fromId, code);
        }
    }

    @SuppressWarnings("unchecked")
    private void fetchAndUpdate(int fromCurrencyId, String fromCode) {
        Map<String, Object> body;
        try {
            body = restClient.get()
                    .uri(API_URL + fromCode)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.warn("Exchange rate fetch failed for {}: {}", fromCode, e.getMessage());
            return;
        }

        if (body == null || !body.containsKey("rates")) {
            log.warn("Unexpected response from exchange rate API for {}", fromCode);
            return;
        }

        Map<String, Object> rates = (Map<String, Object>) body.get("rates");

        // Update all non-stablecoin pairs where from_currency = fromCurrencyId
        List<Map<String, Object>> targets = jdbc.queryForList("""
                SELECT er.to_currency_id, c.code AS to_code
                FROM digitaltwinapp.exchange_rates er
                JOIN digitaltwinapp.currencies c ON c.id = er.to_currency_id
                WHERE er.from_currency_id = ? AND er.is_stablecoin_pair = false
                """, fromCurrencyId);

        for (Map<String, Object> target : targets) {
            int    toId   = ((Number) target.get("to_currency_id")).intValue();
            String toCode = (String) target.get("to_code");

            // USDC/USDT aren't standard ISO codes — treat them as USD for rate lookup
            String lookupCode = toCode.startsWith("USD") ? "USD" : toCode;
            Object rawRate = rates.get(lookupCode);
            if (rawRate == null) {
                log.warn("No rate found for {} → {} (looked up {})", fromCode, toCode, lookupCode);
                continue;
            }

            BigDecimal rate = new BigDecimal(rawRate.toString()).setScale(8, RoundingMode.HALF_UP);
            jdbc.update("""
                    UPDATE digitaltwinapp.exchange_rates
                    SET rate = ?, last_updated = now()
                    WHERE from_currency_id = ? AND to_currency_id = ?
                    """, rate, fromCurrencyId, toId);

            log.debug("Updated rate {} → {}: {}", fromCode, toCode, rate);
        }

        log.info("Exchange rates refreshed for base currency {}", fromCode);
    }
}
