package com.matera.digitaltwin.backoffice.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WalletInfoResponse {
    String address;
    String publicKey;
    /** Base58-encoded private key — only returned on wallet creation, never persisted. */
    String privateKey;
    String network;
}
