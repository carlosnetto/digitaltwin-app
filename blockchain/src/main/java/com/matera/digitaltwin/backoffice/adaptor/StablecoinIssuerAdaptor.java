package com.matera.digitaltwin.backoffice.adaptor;

import com.matera.digitaltwin.backoffice.dto.request.CircleBurnRequest;
import com.matera.digitaltwin.backoffice.dto.request.CircleMintRequest;
import com.matera.digitaltwin.backoffice.dto.response.BalanceResponse;
import com.matera.digitaltwin.backoffice.dto.response.CircleOperationResponse;

import java.math.BigDecimal;

/**
 * Core interface for stablecoin issuer adaptors.
 * Implement once per issuer (Circle, Paxos, etc.).
 */
public interface StablecoinIssuerAdaptor {

    /** Canonical issuer identifier, e.g. "circle", "paxos". */
    String getIssuer();

    /** Account balance held at the issuer (not on-chain). */
    BalanceResponse getAccountBalance(String accountId);

    /** Create (mint) new stablecoin units. Returns an operation that may be async. */
    CircleOperationResponse mint(CircleMintRequest request);

    /** Redeem (burn) stablecoin units back to the issuer. */
    CircleOperationResponse burn(CircleBurnRequest request);

    /**
     * Transfer stablecoin from an issuer account to an on-chain address.
     *
     * @param chain target chain identifier, e.g. "SOL", "ETH", "BASE"
     */
    CircleOperationResponse transfer(String fromAccountId, String toAddress,
                                     BigDecimal amount, String chain);
}
