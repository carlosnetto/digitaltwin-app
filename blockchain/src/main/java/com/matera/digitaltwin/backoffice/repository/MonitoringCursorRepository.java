package com.matera.digitaltwin.backoffice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class MonitoringCursorRepository {

    private final JdbcTemplate jdbc;

    /** Returns all blockchains being monitored and their current cursor. */
    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList(
                "SELECT blockchain_id, last_tx_hash FROM blockchain_schema.monitoring_cursors");
    }

    /** Returns the last processed tx_hash for a blockchain, or null if none yet. */
    public String getLastTxHash(String blockchainId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT last_tx_hash FROM blockchain_schema.monitoring_cursors
                WHERE blockchain_id = ?
                """, blockchainId);
        if (rows.isEmpty()) return null;
        return (String) rows.get(0).get("last_tx_hash");
    }

    /**
     * Updates the cursor to the given tx_hash.
     * Must be called within the same DB transaction as the corresponding insert
     * in received_transactions to guarantee ACID sync.
     */
    public void updateCursor(String blockchainId, String txHash) {
        jdbc.update("""
                UPDATE blockchain_schema.monitoring_cursors
                SET last_tx_hash = ?, updated_at = now()
                WHERE blockchain_id = ?
                """, txHash, blockchainId);
    }
}
