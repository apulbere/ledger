--liquibase formatted sql

--changeset ledger:create-schema-journal
CREATE SCHEMA IF NOT EXISTS journal;
