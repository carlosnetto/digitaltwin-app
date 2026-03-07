package com.matera.digitaltwin.backoffice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TokenRepository {

    private final JdbcTemplate jdbc;

    /** Returns all tokens supported on a given blockchain. */
    public List<Map<String, Object>> findAllByBlockchain(String blockchainId) {
        return jdbc.queryForList("""
                SELECT id, name, mint_address, decimals
                FROM blockchain_schema.tokens
                WHERE blockchain_id = ?
                """, blockchainId);
    }

    /** Returns token metadata (mint_address, decimals, name) for the given token + chain. */
    public Optional<Map<String, Object>> find(String tokenId, String blockchainId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, mint_address, decimals
                FROM blockchain_schema.tokens
                WHERE id = ? AND blockchain_id = ?
                """, tokenId, blockchainId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
