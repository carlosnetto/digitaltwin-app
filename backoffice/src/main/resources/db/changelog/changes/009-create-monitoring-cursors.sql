--liquibase formatted sql

-- ─────────────────────────────────────────────────────────────────────────────
-- monitoring_cursors — one row per blockchain, tracks resume position.
--
-- last_tx_hash: the last transaction signature successfully inserted into
-- received_transactions. Updated atomically in the same DB transaction as
-- the insert, so it is always in sync (ACID).
-- On restart: resume polling from this signature — anything newer is unknown.
-- ─────────────────────────────────────────────────────────────────────────────
--changeset digitaltwin:009-create-monitoring-cursors
CREATE TABLE blockchain_schema.monitoring_cursors (
    blockchain_id VARCHAR(20)  PRIMARY KEY REFERENCES blockchain_schema.blockchains(id),
    last_tx_hash  TEXT,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed one row per supported blockchain
INSERT INTO blockchain_schema.monitoring_cursors (blockchain_id, last_tx_hash)
VALUES ('solana', NULL);
--rollback DROP TABLE blockchain_schema.monitoring_cursors;
