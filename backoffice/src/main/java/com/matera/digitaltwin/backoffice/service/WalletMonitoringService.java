package com.matera.digitaltwin.backoffice.service;

import com.matera.digitaltwin.backoffice.adaptor.solana.SolanaAdaptor;
import com.matera.digitaltwin.backoffice.dto.response.TransactionResponse;
import com.matera.digitaltwin.backoffice.repository.MonitoringCursorRepository;
import com.matera.digitaltwin.backoffice.repository.TokenRepository;
import com.matera.digitaltwin.backoffice.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Polls the blockchain for incoming transactions to any managed wallet.
 * Runs on a fixed delay. On restart, resumes from the last persisted cursor.
 *
 * Current implementation: per-address polling via getSignaturesForAddress.
 * Future upgrade path: replace with Yellowstone gRPC stream (one connection,
 * server-side filtering) — the persistence layer stays unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletMonitoringService {

    private static final int FETCH_LIMIT = 50;

    private final WalletRepository walletRepository;
    private final TokenRepository tokenRepository;
    private final MonitoringCursorRepository cursorRepository;
    private final MonitoringPersistenceService persistenceService;
    private final SolanaAdaptor solanaAdaptor;

    @Scheduled(fixedDelayString = "${monitoring.poll-interval-ms:30000}")
    public void poll() {
        List<Map<String, Object>> cursors = cursorRepository.findAll();
        for (Map<String, Object> cursor : cursors) {
            String blockchainId = (String) cursor.get("blockchain_id");
            try {
                pollBlockchain(blockchainId);
            } catch (Exception e) {
                log.error("Monitoring poll failed for blockchain={}: {}", blockchainId, e.getMessage());
            }
        }
    }

    private void pollBlockchain(String blockchainId) {
        List<String> addresses = walletRepository.findAllAddressesByBlockchain(blockchainId);
        if (addresses.isEmpty()) return;

        List<Map<String, Object>> tokens = tokenRepository.findAllByBlockchain(blockchainId);
        if (tokens.isEmpty()) return;

        // Build a reverse lookup: mintAddress → tokenId
        Map<String, String> mintToTokenId = tokens.stream().collect(
                Collectors.toMap(t -> (String) t.get("mint_address"), t -> (String) t.get("id")));

        Set<String> addressSet = Set.copyOf(addresses);

        for (String address : addresses) {
            for (Map<String, Object> token : tokens) {
                String mintAddress = (String) token.get("mint_address");
                String tokenId = (String) token.get("id");
                pollAddressToken(blockchainId, address, tokenId, mintAddress, addressSet, mintToTokenId);
            }
        }
    }

    private void pollAddressToken(String blockchainId, String address, String tokenId,
                                  String mintAddress, Set<String> managedAddresses,
                                  Map<String, String> mintToTokenId) {
        try {
            List<TransactionResponse> history = solanaAdaptor.getTokenTransactionHistory(
                    address, mintAddress, FETCH_LIMIT);

            for (TransactionResponse tx : history) {
                // Only process confirmed incoming transfers to one of our managed addresses
                if (!"confirmed".equals(tx.getStatus())) continue;
                if (tx.getToAddress() == null) continue;
                if (!managedAddresses.contains(tx.getToAddress())) continue;
                if (tx.getAmount() == null || tx.getAmount() <= 0) continue;

                persistenceService.persistAndAdvanceCursor(
                        tx.getTxHash(),
                        blockchainId,
                        tx.getToAddress(),
                        tokenId,
                        mintAddress,
                        tx.getAmount(),
                        null,          // memo: not yet parsed from transaction
                        tx.getTimestamp());
            }
        } catch (Exception e) {
            log.warn("Poll failed for address={} token={}: {}", address, tokenId, e.getMessage());
        }
    }
}
