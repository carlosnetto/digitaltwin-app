package com.matera.digitaltwin.backoffice.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class TransactionResponse {
    String txHash;
    String fromAddress;
    String toAddress;
    /** Raw units (lamports for SOL, micro-units for SPL tokens). Null if unknown. */
    Long amount;
    String currency;
    /** "confirmed", "finalized", "failed", "unknown" */
    String status;
    Instant timestamp;
    Long slot;
    String explorerUrl;
}
