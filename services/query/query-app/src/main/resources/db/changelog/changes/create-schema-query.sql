--liquibase formatted sql

--changeset ledger:create-schema-query
CREATE SCHEMA IF NOT EXISTS query;
