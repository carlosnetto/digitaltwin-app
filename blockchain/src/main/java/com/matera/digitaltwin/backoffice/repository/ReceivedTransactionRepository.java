package com.matera.digitaltwin.backoffice.repository;

import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ReceivedTransactionRepository {

    private final JdbcTemplate jdbc;

    /**
     * Inserts a received transaction.
     * Uses ON CONFLICT DO NOTHING — safe to call multiple times for the same tx_hash.
     * Returns true if a new row was inserted, false if it was a duplicate.
     */
    public boolean insertIfAbsent(String txHash, String blockchainId, String toPublicAddress,
                                  String tokenId, String mintAddress, long amount,
                                  String memo, Instant blockchainConfirmedAt) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        int rows = jdbc.update("""
                INSERT INTO blockchain_schema.received_transactions
                    (id, tx_hash, blockchain_id, to_public_address,
                     token_id, mint_address, amount, memo, blockchain_confirmed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tx_hash) DO NOTHING
                """,
                id, txHash, blockchainId, toPublicAddress,
                tokenId, mintAddress, amount, memo,
                blockchainConfirmedAt != null
                        ? java.sql.Timestamp.from(blockchainConfirmedAt) : null);
        return rows > 0;
    }
}
