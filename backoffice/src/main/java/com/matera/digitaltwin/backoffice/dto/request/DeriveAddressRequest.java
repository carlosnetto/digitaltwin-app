package com.matera.digitaltwin.backoffice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeriveAddressRequest {

    /** BIP39 mnemonic — 12 or 24 words, space-separated. */
    @NotBlank(message = "mnemonic is required")
    private String mnemonic;

    /**
     * BIP44 account index (0-based). Each index produces a unique address.
     * Use 0 for the first/default account of a standard Solana wallet.
     */
    @Min(value = 0, message = "accountIndex must be >= 0")
    private int accountIndex = 0;
}
