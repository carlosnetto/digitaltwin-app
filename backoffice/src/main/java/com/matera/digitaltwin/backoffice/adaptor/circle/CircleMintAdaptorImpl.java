package com.matera.digitaltwin.backoffice.adaptor.circle;

import com.matera.digitaltwin.backoffice.dto.request.CircleBurnRequest;
import com.matera.digitaltwin.backoffice.dto.request.CircleMintRequest;
import com.matera.digitaltwin.backoffice.dto.response.BalanceResponse;
import com.matera.digitaltwin.backoffice.dto.response.CircleOperationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Circle Mint Account adaptor.
 *
 * <p>Uses Spring WebClient (reactive) to call the Circle v1 API.
 * All methods block synchronously via {@code .block()} because the REST controllers
 * expose a synchronous API surface; swap to reactive chain if needed.
 *
 * <p>API reference: https://developers.circle.com/reference
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class CircleMintAdaptorImpl implements CircleMintAdaptor {

    private final WebClient circleWebClient;

    // ──────────────────────────── StablecoinIssuerAdaptor ─────────────────────

    @Override
    public String getIssuer() {
        return "circle";
    }

    @Override
    public BalanceResponse getAccountBalance(String accountId) {
        Map<String, Object> body = circleWebClient.get()
                .uri("/accounts/{id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .block();

        Map<String, Object> data = extractData(body);
        List<Map<String, Object>> balances =
                (List<Map<String, Object>>) data.getOrDefault("balances", List.of());

        // Circle returns an array of {amount, currency}; use the first entry
        BigDecimal amount = BigDecimal.ZERO;
        String currency = "USDC";
        if (!balances.isEmpty()) {
            Map<String, Object> first = balances.get(0);
            amount = new BigDecimal(first.get("amount").toString());
            currency = first.get("currency").toString();
        }

        return BalanceResponse.builder()
                .address(accountId)
                .balance(amount)
                .currency(currency)
                .network("circle")
                .build();
    }

    @Override
    public CircleOperationResponse mint(CircleMintRequest request) {
        Map<String, Object> payload = Map.of(
                "idempotencyKey", request.getIdempotencyKey(),
                "amount", Map.of(
                        "amount", request.getAmount().toPlainString(),
                        "currency", request.getCurrency()
                ),
                "toAmount", Map.of("currency", request.getCurrency()),
                "source", Map.of(
                        "type", "wallet",
                        "id", request.getAccountId()
                ),
                "destination", Map.of(
                        "type", "blockchain",
                        "address", request.getDestinationAddress(),
                        "chain", request.getChain()
                )
        );

        Map<String, Object> body = circleWebClient.post()
                .uri("/transfers")
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .block();

        return mapToOperation(extractData(body), "mint");
    }

    @Override
    public CircleOperationResponse burn(CircleBurnRequest request) {
        Map<String, Object> payload = Map.of(
                "idempotencyKey", request.getIdempotencyKey(),
                "amount", Map.of(
                        "amount", request.getAmount().toPlainString(),
                        "currency", request.getCurrency()
                ),
                "source", Map.of(
                        "type", "blockchain",
                        "address", request.getSourceAddress(),
                        "chain", request.getChain()
                ),
                "destination", Map.of(
                        "type", "wallet",
                        "id", request.getAccountId()
                )
        );

        Map<String, Object> body = circleWebClient.post()
                .uri("/transfers")
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .block();

        return mapToOperation(extractData(body), "burn");
    }

    @Override
    public CircleOperationResponse transfer(String fromAccountId, String toAddress,
                                            BigDecimal amount, String chain) {
        Map<String, Object> payload = Map.of(
                "idempotencyKey", UUID.randomUUID().toString(),
                "source", Map.of("type", "wallet", "id", fromAccountId),
                "destination", Map.of(
                        "type", "blockchain",
                        "address", toAddress,
                        "chain", chain
                ),
                "amount", Map.of("amount", amount.toPlainString(), "currency", "USDC")
        );

        Map<String, Object> body = circleWebClient.post()
                .uri("/transfers")
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .block();

        return mapToOperation(extractData(body), "transfer");
    }

    // ──────────────────────────── CircleMintAdaptor ────────────────────────────

    @Override
    public List<CircleOperationResponse> listMints(String accountId) {
        Map<String, Object> body = circleWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/transfers")
                        .queryParam("sourceWalletId", accountId)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .block();

        List<Map<String, Object>> items =
                (List<Map<String, Object>>) body.getOrDefault("data", List.of());
        return items.stream()
                .map(item -> mapToOperation(item, "mint"))
                .toList();
    }

    @Override
    public CircleOperationResponse getOperationStatus(String operationId) {
        Map<String, Object> body = circleWebClient.get()
                .uri("/transfers/{id}", operationId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .block();

        return mapToOperation(extractData(body), "transfer");
    }

    // ──────────────────────────── private helpers ───────────────────────────────

    private Map<String, Object> extractData(Map<String, Object> body) {
        if (body == null) throw new RuntimeException("Empty response from Circle API");
        Object data = body.get("data");
        if (data instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new RuntimeException("Unexpected Circle API response shape: " + body);
    }

    private CircleOperationResponse mapToOperation(Map<String, Object> data, String operationType) {
        Object amountObj = data.get("amount");
        BigDecimal amount = BigDecimal.ZERO;
        String currency = "USDC";
        String chain = null;

        if (amountObj instanceof Map<?, ?> amountMap) {
            if (amountMap.get("amount") != null) {
                amount = new BigDecimal(amountMap.get("amount").toString());
            }
            if (amountMap.get("currency") != null) {
                currency = amountMap.get("currency").toString();
            }
        }

        Object destObj = data.get("destination");
        if (destObj instanceof Map<?, ?> destination && destination.get("chain") != null) {
            chain = destination.get("chain").toString();
        }

        return CircleOperationResponse.builder()
                .operationId(safeString(data.get("id")))
                .status(safeString(data.get("status")))
                .operationType(operationType)
                .amount(amount)
                .currency(currency)
                .chain(chain)
                .txHash(safeString(data.get("transactionHash")))
                .createDate(parseInstant(data.get("createDate")))
                .updateDate(parseInstant(data.get("updateDate")))
                .build();
    }

    private Mono<? extends Throwable> handleError(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.bodyToMono(String.class).map(body -> {
            log.error("Circle API error {}: {}", response.statusCode(), body);
            return (Throwable) new RuntimeException(
                    "Circle API error " + response.statusCode() + ": " + body);
        });
    }

    private Instant parseInstant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String safeString(Object value) {
        return value != null ? value.toString() : null;
    }
}
