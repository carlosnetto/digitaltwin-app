--liquibase formatted sql

-- ─────────────────────────────────────────────────────────────────────────────
-- blockchains — one row per supported chain
-- coin_type: BIP44 coin type used in derivation path m/44'/{coin_type}'/index'/0'
-- ─────────────────────────────────────────────────────────────────────────────
--changeset digitaltwin:002-create-blockchains
CREATE TABLE solana_schema.blockchains (
    id          VARCHAR(20)  PRIMARY KEY,        -- e.g. 'solana', 'ethereum'
    name        VARCHAR(100) NOT NULL,
    coin_type   INTEGER      NOT NULL            -- BIP44: 501=Solana, 60=Ethereum
);

INSERT INTO solana_schema.blockchains (id, name, coin_type)
VALUES ('solana', 'Solana', 501);
--rollback DROP TABLE solana_schema.blockchains;


-- ─────────────────────────────────────────────────────────────────────────────
-- seed_groups — one row per mnemonic set (12/24 words)
-- the mnemonic itself is NEVER stored here or anywhere
-- ─────────────────────────────────────────────────────────────────────────────
--changeset digitaltwin:002-create-seed-groups
CREATE TABLE solana_schema.seed_groups (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    label       VARCHAR(255) NOT NULL,           -- human description shown at startup prompt
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
--rollback DROP TABLE solana_schema.seed_groups;


-- ─────────────────────────────────────────────────────────────────────────────
-- wallets — one row per public address
-- (seed_group_id, derivation_index, blockchain_id) is the re-derivation recipe
-- ─────────────────────────────────────────────────────────────────────────────
--changeset digitaltwin:002-create-wallets
CREATE TABLE solana_schema.wallets (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    seed_group_id     UUID         NOT NULL REFERENCES solana_schema.seed_groups(id),
    blockchain_id     VARCHAR(20)  NOT NULL REFERENCES solana_schema.blockchains(id),
    derivation_index  INTEGER      NOT NULL,
    public_address    TEXT         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- prevents assigning the same index twice within the same group + chain
    CONSTRAINT uq_wallet_derivation UNIQUE (seed_group_id, derivation_index, blockchain_id),

    -- prevents duplicate addresses per chain
    CONSTRAINT uq_wallet_address    UNIQUE (blockchain_id, public_address)
);
--rollback DROP TABLE solana_schema.wallets;
