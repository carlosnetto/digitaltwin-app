package com.matera.digitaltwin.backoffice.adaptor.solana;

import com.matera.digitaltwin.backoffice.adaptor.BlockchainAdaptor;
import com.matera.digitaltwin.backoffice.dto.response.TransactionResponse;
import com.matera.digitaltwin.backoffice.dto.response.WalletInfoResponse;

import java.util.List;

/**
 * Solana-specific extensions on top of {@link BlockchainAdaptor}.
 */
public interface SolanaAdaptor extends BlockchainAdaptor {

    /**
     * Derive a Solana wallet (address + private key) from a BIP39 mnemonic using
     * SLIP-0010 ed25519 derivation at path m/44'/501'/{accountIndex}'/0'.
     */
    WalletInfoResponse deriveAddress(String mnemonic, int accountIndex);

    /**
     * Transaction history for a specific SPL token (e.g. USDC).
     * Queries the Associated Token Account (ATA) derived from the wallet address + mint,
     * so results contain only transfers involving that token.
     */
    List<TransactionResponse> getTokenTransactionHistory(String address, String tokenMint, int limit);

    /**
     * Estimate the current transaction fee in lamports for a simple transfer.
     */
    long estimateFee(String fromAddress, String toAddress);
}
