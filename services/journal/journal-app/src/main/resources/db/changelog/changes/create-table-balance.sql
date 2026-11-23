--liquibase formatted sql

--changeset ledger:create-table-balance
CREATE TABLE journal.balance (
    account_id          text    NOT NULL,
    currency            char(3) NOT NULL,
    available           bigint  NOT NULL,
    held                bigint  NOT NULL,
    version             bigint  NOT NULL,
    last_transaction_id uuid    NOT NULL,
    PRIMARY KEY (account_id, currency)
);
