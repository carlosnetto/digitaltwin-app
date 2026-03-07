--liquibase formatted sql

--changeset digitaltwin:003-rename-schema
ALTER SCHEMA solana_schema RENAME TO blockchain_schema;
--rollback ALTER SCHEMA blockchain_schema RENAME TO solana_schema;
