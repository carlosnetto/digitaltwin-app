--liquibase formatted sql

-- ─────────────────────────────────────────────────────────────────────────────
-- received_transactions — immutable ledger of incoming credits observed on-chain
--
-- tx_hash is UNIQUE — used for deduplication via INSERT ... ON CONFLICT DO NOTHING.
-- token_id is nullable: set when the token is in our tokens table, null otherwise.
-- memo is nullable and schemaless: stored raw as the chain delivers it.
-- blockchain_confirmed_at: block timestamp from the chain, may be null if unavailable.
-- ─────────────────────────────────────────────────────────────────────────────
--changeset digitaltwin:008-create-received-transactions
CREATE TABLE blockchain_schema.received_transactions (
    id                      UUID        PRIMARY KEY,
    tx_hash                 TEXT        NOT NULL,
    blockchain_id           VARCHAR(20) NOT NULL REFERENCES blockchain_schema.blockchains(id),
    to_public_address       TEXT        NOT NULL,
    token_id                VARCHAR(20),
    mint_address            TEXT        NOT NULL,
    amount                  BIGINT      NOT NULL,
    memo                    TEXT,
    blockchain_confirmed_at TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_received_tx_hash UNIQUE (tx_hash)
);
--rollback DROP TABLE blockchain_schema.received_transactions;
