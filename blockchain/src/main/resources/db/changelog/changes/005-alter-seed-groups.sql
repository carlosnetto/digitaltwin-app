--liquibase formatted sql

-- active: only one row should be true at a time — new wallets are derived from this group
-- next_derivation_index: atomically incremented per wallet creation; avoids SELECT MAX()
--changeset digitaltwin:005-alter-seed-groups
ALTER TABLE blockchain_schema.seed_groups
    ADD COLUMN active                BOOLEAN  NOT NULL DEFAULT false,
    ADD COLUMN next_derivation_index INTEGER  NOT NULL DEFAULT 0;
--rollback ALTER TABLE blockchain_schema.seed_groups DROP COLUMN active, DROP COLUMN next_derivation_index;
