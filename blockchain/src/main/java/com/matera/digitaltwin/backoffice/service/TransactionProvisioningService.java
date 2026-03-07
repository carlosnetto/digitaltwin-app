package com.matera.digitaltwin.backoffice.service;

import com.matera.digitaltwin.backoffice.adaptor.solana.SolanaAdaptor;
import com.matera.digitaltwin.backoffice.dto.response.SendResultResponse;
import com.matera.digitaltwin.backoffice.dto.response.SendTokenResponse;
import com.matera.digitaltwin.backoffice.dto.response.WalletInfoResponse;
import com.matera.digitaltwin.backoffice.repository.TokenRepository;
import com.matera.digitaltwin.backoffice.repository.TransactionRepository;
import com.matera.digitaltwin.backoffice.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionProvisioningService {

    private final SeedGroupRegistry seedGroupRegistry;
    private final WalletRepository walletRepository;
    private final TokenRepository tokenRepository;
    private final TransactionRepository transactionRepository;
    private final SolanaAdaptor solanaAdaptor;

    /**
     * Sends tokens from a managed wallet to any address.
     * Idempotent on requestId — returns the stored result if already processed.
     *
     * @param requestId          caller idempotency key
     * @param blockchainId       target chain (e.g. "solana")
     * @param fromPublicAddress  source wallet address (must be in our wallets table)
     * @param toAddress          destination address
     * @param tokenId            token symbol (e.g. "USDC")
     * @param amount             raw units (e.g. 1_000_000 = 1 USDC)
     */
    @Transactional
    public SendTokenResponse send(UUID requestId, String blockchainId, String fromPublicAddress,
                                  String toAddress, String tokenId, long amount) {

        // ── 1. Idempotency check ──────────────────────────────────────────────
        var existing = transactionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            Map<String, Object> row = existing.get();
            log.info("Idempotent return for requestId={}", requestId);
            return buildResponse(requestId, blockchainId, fromPublicAddress,
                    toAddress, tokenId, amount, row);
        }

        // ── 2. Resolve wallet ─────────────────────────────────────────────────
        Map<String, Object> wallet = walletRepository
                .findByAddress(fromPublicAddress, blockchainId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Wallet not found: " + fromPublicAddress + " on " + blockchainId));

        // ── 3. Resolve token ──────────────────────────────────────────────────
        Map<String, Object> token = tokenRepository
                .find(tokenId, blockchainId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Token not found: " + tokenId + " on " + blockchainId));

        UUID walletId = (UUID) wallet.get("id");
        UUID seedGroupId = (UUID) wallet.get("seed_group_id");
        int derivationIndex = ((Number) wallet.get("derivation_index")).intValue();
        String mintAddress = (String) token.get("mint_address");

        // ── 4. Register as PENDING ────────────────────────────────────────────
        UUID txId = transactionRepository.insertPending(
                requestId, walletId, blockchainId, toAddress, amount, mintAddress);

        try {
            // ── 5. Derive private key in memory ───────────────────────────────
            String mnemonic = seedGroupRegistry.getMnemonic(seedGroupId);
            WalletInfoResponse keyPair = solanaAdaptor.deriveAddress(mnemonic, derivationIndex);

            // ── 6. Submit to blockchain ───────────────────────────────────────
            SendResultResponse result = submitToChain(
                    blockchainId, keyPair.getPrivateKey(), toAddress, mintAddress, amount);

            // ── 7. Mark SUBMITTED ─────────────────────────────────────────────
            transactionRepository.markSubmitted(txId, result.getTxHash());

            log.info("Transaction submitted: {} {} from {} to {} txHash={}",
                    amount, tokenId, fromPublicAddress, toAddress, result.getTxHash());

            return SendTokenResponse.builder()
                    .requestId(requestId)
                    .blockchainId(blockchainId)
                    .fromPublicAddress(fromPublicAddress)
                    .toAddress(toAddress)
                    .tokenId(tokenId)
                    .amount(amount)
                    .status("SUBMITTED")
                    .txHash(result.getTxHash())
                    .explorerUrl(result.getExplorerUrl())
                    .build();

        } catch (Exception e) {
            transactionRepository.markFailed(txId, e.getMessage());
            throw e;
        }
    }

    private SendResultResponse submitToChain(String blockchainId, String privateKey,
                                             String toAddress, String mintAddress, long amount) {
        return switch (blockchainId) {
            case "solana" -> solanaAdaptor.sendToken(privateKey, toAddress, mintAddress, amount);
            default -> throw new IllegalArgumentException("Unsupported blockchain: " + blockchainId);
        };
    }

    private SendTokenResponse buildResponse(UUID requestId, String blockchainId,
                                            String fromPublicAddress, String toAddress,
                                            String tokenId, long amount, Map<String, Object> row) {
        return SendTokenResponse.builder()
                .requestId(requestId)
                .blockchainId(blockchainId)
                .fromPublicAddress(fromPublicAddress)
                .toAddress(toAddress)
                .tokenId(tokenId)
                .amount(amount)
                .status((String) row.get("status"))
                .txHash((String) row.get("tx_hash"))
                .build();
    }
}
