--liquibase formatted sql

-- ─────────────────────────────────────────────────────────────────────────────
-- tokens — supported tokens per blockchain
--
-- PK is (id, blockchain_id) so the same token symbol can exist on multiple
-- chains with different mint addresses and decimal places.
-- decimals: raw-unit scale (e.g. 6 means 1 token = 1_000_000 raw units)
-- ─────────────────────────────────────────────────────────────────────────────
--changeset digitaltwin:007-create-tokens
CREATE TABLE blockchain_schema.tokens (
    id            VARCHAR(20)  NOT NULL,
    blockchain_id VARCHAR(20)  NOT NULL REFERENCES blockchain_schema.blockchains(id),
    name          VARCHAR(100) NOT NULL,
    mint_address  TEXT         NOT NULL,
    decimals      INTEGER      NOT NULL,
    PRIMARY KEY (id, blockchain_id)
);

INSERT INTO blockchain_schema.tokens (id, blockchain_id, name, mint_address, decimals) VALUES
    ('USDC', 'solana', 'USD Coin',  'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', 6),
    ('USDT', 'solana', 'Tether USD', 'Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB', 6);
--rollback DROP TABLE blockchain_schema.tokens;
