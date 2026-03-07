package com.matera.digitaltwin.backoffice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendSolRequest {

    @NotBlank(message = "fromPrivateKey is required")
    private String fromPrivateKey;

    @NotBlank(message = "toAddress is required")
    private String toAddress;

    /** Raw lamports (1 SOL = 1_000_000_000 lamports). */
    @Min(value = 1, message = "amount must be at least 1 lamport")
    private long amount;
}
