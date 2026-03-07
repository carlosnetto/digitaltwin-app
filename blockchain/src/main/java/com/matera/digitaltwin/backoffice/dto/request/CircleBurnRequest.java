package com.matera.digitaltwin.backoffice.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CircleBurnRequest {

    @NotBlank(message = "accountId is required")
    private String accountId;

    @NotNull
    @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
    private BigDecimal amount;

    /** Currency to redeem, e.g. "USDC". */
    @NotBlank
    private String currency;

    /** Source chain holding the tokens, e.g. "SOL", "ETH". */
    @NotBlank
    private String chain;

    /** Source on-chain wallet address whose tokens will be burned. */
    @NotBlank
    private String sourceAddress;

    /** Client-generated idempotency key (UUID recommended). */
    @NotBlank
    private String idempotencyKey;
}
