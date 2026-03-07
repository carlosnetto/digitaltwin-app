package com.matera.digitaltwin.backoffice.service;

import com.matera.digitaltwin.backoffice.repository.MonitoringCursorRepository;
import com.matera.digitaltwin.backoffice.repository.ReceivedTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Handles the atomic unit of work for each received transaction:
 * INSERT into received_transactions + UPDATE monitoring_cursors in one DB transaction.
 * ACID guarantee: last_tx_hash is always the last successfully inserted tx.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringPersistenceService {

    private final ReceivedTransactionRepository receivedTransactionRepository;
    private final MonitoringCursorRepository monitoringCursorRepository;

    @Transactional
    public boolean persistAndAdvanceCursor(String txHash, String blockchainId,
                                           String toPublicAddress, String tokenId,
                                           String mintAddress, long amount,
                                           String memo, Instant blockchainConfirmedAt) {
        boolean inserted = receivedTransactionRepository.insertIfAbsent(
                txHash, blockchainId, toPublicAddress,
                tokenId, mintAddress, amount, memo, blockchainConfirmedAt);

        // Advance cursor regardless of whether it was a duplicate —
        // we still processed this signature and don't want to re-visit it.
        monitoringCursorRepository.updateCursor(blockchainId, txHash);

        if (inserted) {
            log.info("Received: {} {} → {} txHash={}", amount, tokenId, toPublicAddress, txHash);
        }
        return inserted;
    }
}
