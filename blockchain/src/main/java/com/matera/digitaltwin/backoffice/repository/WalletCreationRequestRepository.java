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
public class WalletCreationRequestRepository {

    private final JdbcTemplate jdbc;

    /** Finds an existing request by (request_id, blockchain_id) — idempotency check. */
    public Optional<Map<String, Object>> find(UUID requestId, String blockchainId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status, public_address, error_message
                FROM blockchain_schema.wallet_creation_requests
                WHERE request_id = ? AND blockchain_id = ?
                """, requestId, blockchainId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Inserts a new request in PENDING status. Returns the generated UUIDv7. */
    public UUID insertPending(UUID requestId, String blockchainId) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        jdbc.update("""
                INSERT INTO blockchain_schema.wallet_creation_requests
                    (id, request_id, blockchain_id, status)
                VALUES (?, ?, ?, 'PENDING')
                """, id, requestId, blockchainId);
        return id;
    }

    /** Marks the request as FULFILLED and stores the result. */
    public void markFulfilled(UUID id, UUID seedGroupId, int derivationIndex, String publicAddress) {
        jdbc.update("""
                UPDATE blockchain_schema.wallet_creation_requests
                SET status = 'FULFILLED',
                    seed_group_id    = ?,
                    derivation_index = ?,
                    public_address   = ?
                WHERE id = ?
                """, seedGroupId, derivationIndex, publicAddress, id);
    }

    /** Marks the request as FAILED and stores the reason. */
    public void markFailed(UUID id, String errorMessage) {
        jdbc.update("""
                UPDATE blockchain_schema.wallet_creation_requests
                SET status = 'FAILED', error_message = ?
                WHERE id = ?
                """, errorMessage, id);
    }
}
