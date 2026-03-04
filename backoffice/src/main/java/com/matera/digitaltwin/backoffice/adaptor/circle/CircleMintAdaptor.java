package com.matera.digitaltwin.backoffice.adaptor.circle;

import com.matera.digitaltwin.backoffice.adaptor.StablecoinIssuerAdaptor;
import com.matera.digitaltwin.backoffice.dto.response.CircleOperationResponse;

import java.util.List;

/**
 * Circle-specific extensions on top of {@link StablecoinIssuerAdaptor}.
 */
public interface CircleMintAdaptor extends StablecoinIssuerAdaptor {

    /**
     * List all mint operations for the given Circle account.
     */
    List<CircleOperationResponse> listMints(String accountId);

    /**
     * Poll the status of an async Circle operation by its operation ID.
     */
    CircleOperationResponse getOperationStatus(String operationId);
}
