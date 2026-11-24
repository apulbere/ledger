--liquibase formatted sql

-- Debezium streams these tables. Only the table owner may publish them, so journal creates the
-- publication and the connector never autocreates one.
--changeset ledger:create-publication-journal-cdc
CREATE PUBLICATION journal_cdc FOR TABLE journal.transaction, journal.balance;
--rollback DROP PUBLICATION journal_cdc;
