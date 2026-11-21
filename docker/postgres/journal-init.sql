-- Runs once on first start of an empty data volume (docker compose down -v to rerun).
-- Dev-only credentials.

CREATE ROLE journal_user LOGIN PASSWORD 'journal_pass';
CREATE ROLE debezium_user LOGIN REPLICATION PASSWORD 'debezium_pass';

-- journal creates its own schema through Liquibase.
GRANT CREATE ON DATABASE journal TO journal_user;
-- Liquibase tracking tables (service-prefixed) live in public.
GRANT CREATE ON SCHEMA public TO journal_user;
