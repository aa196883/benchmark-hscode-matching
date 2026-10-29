--
--
-- File to be loaded using Flywaydb (https://flywaydb.org/documentation/migrations#sql-based-migrations)
--
-- V1: Initializing tables and indexes
--

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS rag;

CREATE TABLE rag.embeddings (
    embedding_id UUID PRIMARY KEY,
    embedding vector(1536) NOT NULL,
    text TEXT NOT NULL,
    metadata JSONB NOT NULL
);

CREATE UNIQUE INDEX rag_embeddings_code
    ON rag.embeddings ((metadata->>'code'));