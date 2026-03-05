package com.matera.digitaltwin.backoffice.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class CreateWalletResponse {
    UUID requestId;
    String blockchainId;
    String publicAddress;
}
