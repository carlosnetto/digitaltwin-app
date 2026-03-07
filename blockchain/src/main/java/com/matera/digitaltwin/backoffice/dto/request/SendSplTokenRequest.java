package com.matera.digitaltwin.backoffice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendSplTokenRequest {

    @NotBlank(message = "fromPrivateKey is required")
    private String fromPrivateKey;

    @NotBlank(message = "toAddress is required")
    private String toAddress;

    /** SPL token mint address, e.g. USDC or USDT mint on mainnet/devnet. */
    @NotBlank(message = "tokenMint is required")
    private String tokenMint;

    /** Raw token units (USDC/USDT: 1 USDC = 1_000_000 units). */
    @Min(value = 1, message = "amount must be at least 1 raw unit")
    private long amount;
}
