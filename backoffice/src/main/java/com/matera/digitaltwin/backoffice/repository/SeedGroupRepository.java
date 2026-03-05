package com.matera.digitaltwin.backoffice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SeedGroupRepository {

    private final JdbcTemplate jdbc;

    /** Returns all active seed groups — used at startup to prompt the operator. */
    public List<Map<String, Object>> findActive() {
        return jdbc.queryForList(
                "SELECT id, label FROM blockchain_schema.seed_groups WHERE active = true");
    }

    /**
     * Atomically claims the next derivation index for the active seed group.
     * Returns a map with: id, label, next_derivation_index (the claimed value).
     * Throws if no active seed group exists.
     */
    public Map<String, Object> claimNextIndex() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE blockchain_schema.seed_groups
                SET next_derivation_index = next_derivation_index + 1
                WHERE active = true
                RETURNING id, label, next_derivation_index - 1 AS claimed_index
                """);

        if (rows.isEmpty()) {
            throw new IllegalStateException("No active seed group configured");
        }
        return rows.get(0);
    }

    /** Inserts a new seed group. Returns the generated UUID. */
    public UUID insert(String label, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO blockchain_schema.seed_groups (id, label, active, next_derivation_index)
                VALUES (?, ?, ?, 0)
                """, id, label, active);
        return id;
    }
}
