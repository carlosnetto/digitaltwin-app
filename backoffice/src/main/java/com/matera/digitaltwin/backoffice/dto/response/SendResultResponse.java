package com.matera.digitaltwin.backoffice.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SendResultResponse {
    String txHash;
    String fromAddress;
    String toAddress;
    /** Raw units (lamports for SOL, micro-units for SPL tokens). */
    long amount;
    String currency;
    /** Fee in lamports. */
    long fee;
    /** "submitted", "confirmed", "failed" */
    String status;
    String explorerUrl;
}
