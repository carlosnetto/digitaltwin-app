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
public class WalletRepository {

    private final JdbcTemplate jdbc;

    /** Returns all public addresses managed for a given blockchain. */
    public List<String> findAllAddressesByBlockchain(String blockchainId) {
        return jdbc.queryForList("""
                SELECT public_address FROM blockchain_schema.wallets
                WHERE blockchain_id = ?
                """, String.class, blockchainId);
    }

    /** Finds a wallet by public address and blockchain. */
    public Optional<Map<String, Object>> findByAddress(String publicAddress, String blockchainId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, seed_group_id, derivation_index
                FROM blockchain_schema.wallets
                WHERE public_address = ? AND blockchain_id = ?
                """, publicAddress, blockchainId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Inserts a new wallet row. Returns the generated UUIDv7. */
    public UUID insert(UUID seedGroupId, String blockchainId, int derivationIndex, String publicAddress) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        jdbc.update("""
                INSERT INTO blockchain_schema.wallets
                    (id, seed_group_id, blockchain_id, derivation_index, public_address)
                VALUES (?, ?, ?, ?, ?)
                """, id, seedGroupId, blockchainId, derivationIndex, publicAddress);
        return id;
    }
}
