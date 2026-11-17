-- Runs once on first start of an empty data volume (docker compose down -v to rerun).
-- Dev-only credentials.

CREATE ROLE journal_user LOGIN PASSWORD 'journal_pass';
CREATE ROLE query_user LOGIN PASSWORD 'query_pass';
CREATE ROLE debezium_user LOGIN REPLICATION PASSWORD 'debezium_pass';

-- Each service creates its own schema through Liquibase.
GRANT CREATE ON DATABASE ledger TO journal_user, query_user;
-- Liquibase tracking tables (service-prefixed) live in public.
GRANT CREATE ON SCHEMA public TO journal_user, query_user;
