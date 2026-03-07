package com.matera.digitaltwin.backoffice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

import java.util.UUID;

@Value
public class CreateWalletRequest {
    @NotNull
    UUID requestId;
    @NotBlank
    String blockchainId;
}
