package com.matera.digitaltwin.backoffice.service;

import com.matera.digitaltwin.backoffice.adaptor.solana.SolanaAdaptor;
import com.matera.digitaltwin.backoffice.dto.response.WalletInfoResponse;
import com.matera.digitaltwin.backoffice.repository.SeedGroupRepository;
import com.matera.digitaltwin.backoffice.repository.WalletCreationRequestRepository;
import com.matera.digitaltwin.backoffice.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletProvisioningService {

    private final SeedGroupRegistry seedGroupRegistry;
    private final SeedGroupRepository seedGroupRepository;
    private final WalletRepository walletRepository;
    private final WalletCreationRequestRepository requestRepository;
    private final SolanaAdaptor solanaAdaptor;

    /**
     * Creates a new wallet for the given blockchain, or returns the existing one
     * if the same (requestId, blockchainId) was already processed.
     *
     * @param requestId   caller-supplied idempotency key
     * @param blockchainId target chain (e.g. "solana")
     * @return the public address
     */
    @Transactional
    public String createWallet(UUID requestId, String blockchainId) {
        // ── 1. Idempotency check ──────────────────────────────────────────────
        Optional<Map<String, Object>> existing = requestRepository.find(requestId, blockchainId);
        if (existing.isPresent()) {
            Map<String, Object> row = existing.get();
            String status = (String) row.get("status");
            if ("FULFILLED".equals(status)) {
                log.info("Idempotent return for requestId={} blockchain={}", requestId, blockchainId);
                return (String) row.get("public_address");
            }
            if ("FAILED".equals(status)) {
                throw new IllegalStateException(
                        "Previous attempt failed: " + row.get("error_message"));
            }
            // PENDING — previous attempt crashed mid-flight; fall through to retry
        }

        // ── 2. Register the request as PENDING ───────────────────────────────
        UUID trackingId = requestRepository.insertPending(requestId, blockchainId);

        try {
            // ── 3. Verify seed is loaded ──────────────────────────────────────
            if (!seedGroupRegistry.hasAnyLoaded()) {
                throw new IllegalStateException(
                        "No seed loaded. Restart the server and enter the mnemonic.");
            }

            // ── 4. Claim the next derivation index (atomic) ───────────────────
            Map<String, Object> seedGroup = seedGroupRepository.claimNextIndex();
            UUID seedGroupId = (UUID) seedGroup.get("id");
            int derivationIndex = ((Number) seedGroup.get("claimed_index")).intValue();

            // ── 5. Derive the public address in memory ────────────────────────
            String mnemonic = seedGroupRegistry.getMnemonic(seedGroupId);
            String publicAddress = deriveAddress(mnemonic, derivationIndex, blockchainId);

            // ── 6. Persist the wallet ─────────────────────────────────────────
            walletRepository.insert(seedGroupId, blockchainId, derivationIndex, publicAddress);

            // ── 7. Mark request fulfilled ─────────────────────────────────────
            requestRepository.markFulfilled(trackingId, seedGroupId, derivationIndex, publicAddress);

            log.info("Wallet created: blockchain={} index={} address={}", blockchainId, derivationIndex, publicAddress);
            return publicAddress;

        } catch (Exception e) {
            requestRepository.markFailed(trackingId, e.getMessage());
            throw e;
        }
    }

    private String deriveAddress(String mnemonic, int index, String blockchainId) {
        return switch (blockchainId) {
            case "solana" -> {
                WalletInfoResponse info = solanaAdaptor.deriveAddress(mnemonic, index);
                yield info.getAddress();
            }
            default -> throw new IllegalArgumentException("Unsupported blockchain: " + blockchainId);
        };
    }
}
