--liquibase formatted sql

-- Debit and credit totals cannot be split from a net, so they are rebuilt from the log,
-- then checked against the old nets before those are dropped.
--changeset ledger:split-balance-debit-credit splitStatements:false
ALTER TABLE journal.balance
    ADD COLUMN available_debit  bigint NOT NULL DEFAULT 0,
    ADD COLUMN available_credit bigint NOT NULL DEFAULT 0,
    ADD COLUMN held_debit       bigint NOT NULL DEFAULT 0,
    ADD COLUMN held_credit      bigint NOT NULL DEFAULT 0;
WITH totals AS (
    SELECT e->>'accountId' AS account_id,
           e->>'currency'  AS currency,
           sum((e->>'amount')::bigint) FILTER (WHERE e->>'bucket' = 'AVAILABLE' AND e->>'direction' = 'DEBIT')  AS available_debit,
           sum((e->>'amount')::bigint) FILTER (WHERE e->>'bucket' = 'AVAILABLE' AND e->>'direction' = 'CREDIT') AS available_credit,
           sum((e->>'amount')::bigint) FILTER (WHERE e->>'bucket' = 'HELD' AND e->>'direction' = 'DEBIT')       AS held_debit,
           sum((e->>'amount')::bigint) FILTER (WHERE e->>'bucket' = 'HELD' AND e->>'direction' = 'CREDIT')      AS held_credit
    FROM journal.transaction t, jsonb_array_elements(t.entries) e
    WHERE e->>'accountType' = 'CUSTOMER'
    GROUP BY 1, 2)
UPDATE journal.balance b
   SET available_debit  = coalesce(t.available_debit, 0),
       available_credit = coalesce(t.available_credit, 0),
       held_debit       = coalesce(t.held_debit, 0),
       held_credit      = coalesce(t.held_credit, 0)
  FROM totals t
 WHERE b.account_id = t.account_id AND b.currency = t.currency;
DO $$
DECLARE
    mismatches bigint;
BEGIN
    SELECT count(*) INTO mismatches
    FROM journal.balance
    WHERE available_credit - available_debit <> available
       OR held_credit - held_debit <> held;
    IF mismatches > 0 THEN
        RAISE EXCEPTION 'journal.balance: % rows disagree with the posting log', mismatches;
    END IF;
END
$$;
ALTER TABLE journal.balance
    ALTER COLUMN available_debit  DROP DEFAULT,
    ALTER COLUMN available_credit DROP DEFAULT,
    ALTER COLUMN held_debit       DROP DEFAULT,
    ALTER COLUMN held_credit      DROP DEFAULT,
    DROP COLUMN available,
    DROP COLUMN held;
--rollback ALTER TABLE journal.balance
--rollback     ADD COLUMN available bigint,
--rollback     ADD COLUMN held      bigint;
--rollback UPDATE journal.balance
--rollback    SET available = available_credit - available_debit,
--rollback        held      = held_credit - held_debit;
--rollback ALTER TABLE journal.balance
--rollback     ALTER COLUMN available SET NOT NULL,
--rollback     ALTER COLUMN held      SET NOT NULL,
--rollback     DROP COLUMN available_debit,
--rollback     DROP COLUMN available_credit,
--rollback     DROP COLUMN held_debit,
--rollback     DROP COLUMN held_credit;
