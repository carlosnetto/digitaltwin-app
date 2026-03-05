--liquibase formatted sql

--changeset digitaltwin:001-create-schema
CREATE SCHEMA IF NOT EXISTS solana_schema;
--rollback DROP SCHEMA solana_schema;
