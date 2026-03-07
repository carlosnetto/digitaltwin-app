package com.matera.digitaltwin.backoffice.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BalanceResponse {
    String address;
    /** Raw units (lamports for SOL, micro-units for SPL tokens). */
    long balance;
    String currency;
    String network;
}
