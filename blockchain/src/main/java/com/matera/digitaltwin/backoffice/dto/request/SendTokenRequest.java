package com.matera.digitaltwin.backoffice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

import java.util.UUID;

@Value
public class SendTokenRequest {
    @NotNull
    UUID requestId;
    @NotBlank
    String blockchainId;
    /** Public address of the source wallet (must exist in our wallets table). */
    @NotBlank
    String fromPublicAddress;
    @NotBlank
    String toAddress;
    /** Token symbol — must exist in tokens table for the given blockchain (e.g. "USDC"). */
    @NotBlank
    String tokenId;
    /** Amount in raw units (least denomination) — e.g. 1_000_000 = 1 USDC. */
    @Min(1)
    long amount;
}
