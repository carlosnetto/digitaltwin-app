package com.matera.digitaltwin.api.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MiniCoreClient {

    private static final Logger log = LoggerFactory.getLogger(MiniCoreClient.class);

    @Value("${minicore.base-url}")
    private String baseUrl;

    private final RestClient restClient = RestClient.create();

    /**
     * Fetches an account from mini-core by its account_id.
     *
     * @return the account map (contains available_balance, etc.), or null if the call failed
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAccount(long accountId) {
        try {
            return restClient.get()
                    .uri(baseUrl + "/api/accounts/" + accountId)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.warn("mini-core getAccount failed for account_id={}: {}", accountId, e.getMessage());
            return null;
        }
    }

    /**
     * Creates an account in mini-core.
     *
     * @return the minicore account_id, or -1 if the call failed
     */
    public long createAccount(String accountNumber, String currencyCode) {
        var body = Map.of(
                "account_number", accountNumber,
                "product_type",   "DDA",
                "currency_code",  currencyCode,
                "created_by",     "digitaltwinapp-api"
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(baseUrl + "/api/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("account_id")) {
                log.warn("mini-core returned no account_id for account_number={}", accountNumber);
                return -1;
            }

            // mini-core returns account_id as a Number (int or double)
            return ((Number) response.get("account_id")).longValue();

        } catch (Exception e) {
            log.warn("mini-core createAccount failed for account_number={}: {}", accountNumber, e.getMessage());
            return -1;
        }
    }

    /**
     * Fetches all transactions for an account, most recent first.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getTransactions(long accountId) {
        try {
            List<Map<String, Object>> list = restClient.get()
                    .uri(baseUrl + "/api/accounts/" + accountId + "/transactions")
                    .retrieve()
                    .body(List.class);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("mini-core getTransactions failed for account_id={}: {}", accountId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Posts a POSTED transaction in mini-core.
     *
     * @param direction "CREDIT" or "DEBIT"
     * @param description optional; stored in json_payload
     * @return the transaction id, or -1 if the call failed
     */
    public long createTransaction(long accountId, int transactionCode, BigDecimal amount,
                                  String direction, String description) {
        var body = new HashMap<String, Object>();
        body.put("account_id",        accountId);
        body.put("transaction_code",  transactionCode);
        body.put("amount",            amount);
        body.put("direction",         direction);
        body.put("status",            "POSTED");
        body.put("created_by",        "digitaltwinapp-api");
        if (description != null) {
            body.put("json_payload", Map.of("description", description));
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(baseUrl + "/api/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return -1;

            // mini-core may return the field as transaction_id or id
            Object txId = response.containsKey("transaction_id")
                    ? response.get("transaction_id")
                    : response.get("id");
            if (txId == null) return -1;
            return ((Number) txId).longValue();

        } catch (Exception e) {
            log.warn("mini-core createTransaction failed for account_id={}: {}", accountId, e.getMessage());
            return -1;
        }
    }
}
