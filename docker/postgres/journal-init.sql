-- Runs once on first start of an empty data volume (docker compose down -v to rerun).
-- Dev-only credentials.

CREATE ROLE journal_user LOGIN PASSWORD 'journal_pass';
CREATE ROLE debezium_user LOGIN REPLICATION PASSWORD 'debezium_pass';

-- journal creates its own schema through Liquibase.
GRANT CREATE ON DATABASE journal TO journal_user;
-- Liquibase tracking tables (service-prefixed) live in public.
GRANT CREATE ON SCHEMA public TO journal_user;

-- Debezium snapshots and streams what journal creates later through Liquibase, so the grants
-- are defaults on journal_user's future schemas and tables. journal's changesets never name this role.
ALTER DEFAULT PRIVILEGES FOR ROLE journal_user GRANT USAGE ON SCHEMAS TO debezium_user;
ALTER DEFAULT PRIVILEGES FOR ROLE journal_user GRANT SELECT ON TABLES TO debezium_user;
