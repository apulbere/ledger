--liquibase formatted sql

--changeset ledger:create-table-transaction
CREATE TABLE journal.transaction (
    id              uuid        PRIMARY KEY,
    idempotency_key text        NOT NULL CONSTRAINT transaction_idempotency_key_uk UNIQUE,
    reference_id    text,
    occurred_at     timestamptz NOT NULL,
    posted_at       timestamptz NOT NULL DEFAULT now(),
    description     text,
    metadata        jsonb,
    entries         jsonb       NOT NULL,
    versions        jsonb       NOT NULL
);
