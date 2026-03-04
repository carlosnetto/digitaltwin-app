package com.matera.digitaltwin.backoffice.dto.response;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

@Value
@Builder
public class CircleOperationResponse {
    String operationId;
    /** "pending", "running", "complete", "failed" */
    String status;
    String operationType;   // "mint", "burn", "transfer"
    BigDecimal amount;
    String currency;
    String chain;
    /** On-chain tx hash once settled, null while pending. */
    String txHash;
    Instant createDate;
    Instant updateDate;
}
