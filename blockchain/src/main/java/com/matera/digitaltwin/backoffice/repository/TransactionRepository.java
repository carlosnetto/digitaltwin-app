package com.matera.digitaltwin.backoffice.repository;

import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TransactionRepository {

    private final JdbcTemplate jdbc;

    /** Finds an existing transaction by request_id — idempotency check. */
    public Optional<Map<String, Object>> findByRequestId(UUID requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status, tx_hash, error_message
                FROM blockchain_schema.transactions
                WHERE request_id = ?
                """, requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Inserts a new transaction in PENDING status. Returns the generated UUIDv7. */
    public UUID insertPending(UUID requestId, UUID walletId, String blockchainId,
                              String toAddress, long amount, String tokenMint) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        jdbc.update("""
                INSERT INTO blockchain_schema.transactions
                    (id, request_id, wallet_id, blockchain_id, to_address, amount, token_mint, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, id, requestId, walletId, blockchainId, toAddress, amount, tokenMint);
        return id;
    }

    /** Moves status to SUBMITTED and stores the blockchain tx hash. */
    public void markSubmitted(UUID id, String txHash) {
        jdbc.update("""
                UPDATE blockchain_schema.transactions
                SET status = 'SUBMITTED', tx_hash = ?
                WHERE id = ?
                """, txHash, id);
    }

    /** Moves status to FAILED and stores the error reason. */
    public void markFailed(UUID id, String errorMessage) {
        jdbc.update("""
                UPDATE blockchain_schema.transactions
                SET status = 'FAILED', error_message = ?
                WHERE id = ?
                """, errorMessage, id);
    }
}
