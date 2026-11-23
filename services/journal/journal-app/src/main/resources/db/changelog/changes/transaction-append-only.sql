--liquibase formatted sql

-- journal_user owns the table, so REVOKE alone would not stop it.
--changeset ledger:transaction-append-only splitStatements:false
CREATE FUNCTION journal.reject_transaction_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'journal.transaction is append-only: % rejected', TG_OP;
END
$$;
CREATE TRIGGER transaction_append_only
    BEFORE UPDATE OR DELETE ON journal.transaction
    FOR EACH ROW EXECUTE FUNCTION journal.reject_transaction_change();
