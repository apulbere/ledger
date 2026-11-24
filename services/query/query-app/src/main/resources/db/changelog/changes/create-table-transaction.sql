--liquibase formatted sql

--changeset ledger:create-table-transaction
CREATE TABLE query.transaction (
    id           uuid        PRIMARY KEY,
    reference_id text,
    occurred_at  timestamptz NOT NULL,
    posted_at    timestamptz NOT NULL,
    description  text,
    metadata     jsonb,
    entries      jsonb       NOT NULL,
    versions     jsonb       NOT NULL
);
--rollback DROP TABLE query.transaction;
