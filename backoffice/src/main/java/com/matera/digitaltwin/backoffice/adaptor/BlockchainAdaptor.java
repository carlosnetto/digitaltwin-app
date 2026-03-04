package com.matera.digitaltwin.backoffice.adaptor;

import com.matera.digitaltwin.backoffice.dto.response.BalanceResponse;
import com.matera.digitaltwin.backoffice.dto.response.SendResultResponse;
import com.matera.digitaltwin.backoffice.dto.response.TransactionResponse;
import com.matera.digitaltwin.backoffice.dto.response.WalletInfoResponse;

import java.util.List;

/**
 * Core interface for blockchain network adaptors.
 * Implement once per network (Solana, Ethereum, Base, etc.).
 * All amounts are raw integer units (lamports for SOL, micro-units for SPL tokens).
 */
public interface BlockchainAdaptor {

    /** Canonical network identifier, e.g. "solana", "ethereum", "base". */
    String getNetwork();

    /** Generate a new random keypair and return address + public key. */
    WalletInfoResponse generateWallet();

    /** Native coin balance in raw units (lamports for SOL). */
    BalanceResponse getNativeBalance(String address);

    /** SPL / ERC-20 token balance in raw units for the given mint / contract address. */
    BalanceResponse getTokenBalance(String address, String tokenMintOrContract);

    /** Transfer native coin. Amount in raw units (lamports). Private key must be base58 (Solana) or hex (EVM). */
    SendResultResponse sendNative(String fromPrivateKey, String toAddress, long amount);

    /** Transfer an SPL / ERC-20 token. Amount in raw units (e.g. 1_000_000 = 1 USDC). */
    SendResultResponse sendToken(String fromPrivateKey, String toAddress,
                                 String tokenMint, long amount);

    /** Most-recent transactions for an address, newest first. */
    List<TransactionResponse> getTransactionHistory(String address, int limit);

    /** Block-explorer URL for a transaction hash. */
    String getExplorerUrl(String txHash);

    /** Return true once the transaction has at least one confirmation. */
    boolean isConfirmed(String txHash);
}
