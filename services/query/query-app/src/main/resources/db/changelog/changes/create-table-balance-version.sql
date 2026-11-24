--liquibase formatted sql

--changeset ledger:create-table-balance-version
CREATE TABLE query.balance_version (
    account_id          text    NOT NULL,
    currency            char(3) NOT NULL,
    version             bigint  NOT NULL,
    available_debit     bigint  NOT NULL,
    available_credit    bigint  NOT NULL,
    held_debit          bigint  NOT NULL,
    held_credit         bigint  NOT NULL,
    last_transaction_id uuid    NOT NULL,
    PRIMARY KEY (account_id, currency, version)
);
-- The balances a posting produced; no foreign key: the two topics are not ordered relative to each other.
CREATE INDEX balance_version_last_transaction_idx ON query.balance_version (last_transaction_id);
--rollback DROP TABLE query.balance_version;
