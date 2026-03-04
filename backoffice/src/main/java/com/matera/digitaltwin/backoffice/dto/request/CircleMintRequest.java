package com.matera.digitaltwin.backoffice.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CircleMintRequest {

    @NotBlank(message = "accountId is required")
    private String accountId;

    @NotNull
    @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
    private BigDecimal amount;

    /** Currency to mint, e.g. "USDC". */
    @NotBlank
    private String currency;

    /** Target chain, e.g. "SOL", "ETH", "BASE". */
    @NotBlank
    private String chain;

    /** Destination on-chain address for the minted tokens. */
    @NotBlank
    private String destinationAddress;

    /** Client-generated idempotency key (UUID recommended). */
    @NotBlank
    private String idempotencyKey;
}
