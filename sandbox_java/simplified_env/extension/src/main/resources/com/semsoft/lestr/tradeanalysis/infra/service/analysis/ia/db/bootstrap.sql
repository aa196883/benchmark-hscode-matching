-- Run once as database administrator in a dedicated, empty database.
-- Set login passwords separately through your deployment's secret mechanism.
CREATE EXTENSION vector;
CREATE ROLE rag_import LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE rag_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
DO $$ BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO rag_import, rag_reader', current_database());
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA rag AUTHORIZATION rag_import;
REVOKE ALL ON SCHEMA rag FROM PUBLIC;
GRANT USAGE ON SCHEMA rag TO rag_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE rag_import IN SCHEMA rag GRANT SELECT ON TABLES TO rag_reader;
ALTER ROLE rag_reader SET default_transaction_read_only = on;
