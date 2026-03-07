package com.matera.digitaltwin.backoffice.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class SendTokenResponse {
    UUID requestId;
    String blockchainId;
    String fromPublicAddress;
    String toAddress;
    String tokenId;
    long amount;
    String status;
    String txHash;
    String explorerUrl;
}
