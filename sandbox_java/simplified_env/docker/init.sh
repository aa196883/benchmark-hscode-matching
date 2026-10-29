#!/usr/bin/env bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f /opt/rag/bootstrap.sql
# Compose provisions its empty database from the same V1 SQL as Flyway.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -f /opt/rag/V1__init.sql
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
GRANT SELECT, INSERT ON rag.embeddings TO rag_import;
GRANT SELECT ON rag.embeddings TO rag_reader;
\getenv import_password RAG_IMPORT_DB_PASSWORD
\getenv reader_password RAG_DB_PASSWORD
ALTER ROLE rag_import PASSWORD :'import_password';
ALTER ROLE rag_reader PASSWORD :'reader_password';
SQL
