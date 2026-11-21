-- Runs once on first start of an empty data volume (docker compose down -v to rerun).
-- Dev-only credentials.

CREATE ROLE query_user LOGIN PASSWORD 'query_pass';

-- query creates its own schema through Liquibase.
GRANT CREATE ON DATABASE query TO query_user;
-- Liquibase tracking tables (service-prefixed) live in public.
GRANT CREATE ON SCHEMA public TO query_user;
