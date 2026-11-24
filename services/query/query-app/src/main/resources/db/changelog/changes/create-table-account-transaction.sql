--liquibase formatted sql

--changeset ledger:create-table-account-transaction
CREATE TABLE query.account_transaction (
    account_id     text        NOT NULL,
    transaction_id uuid        NOT NULL REFERENCES query.transaction (id),
    occurred_at    timestamptz NOT NULL,
    currencies     char(3)[]   NOT NULL,
    PRIMARY KEY (account_id, transaction_id)
);
-- Search order, occurredAt range and keyset cursor in one index range scan per account.
CREATE INDEX account_transaction_search_idx
    ON query.account_transaction (account_id, occurred_at DESC, transaction_id DESC);
--rollback DROP TABLE query.account_transaction;
