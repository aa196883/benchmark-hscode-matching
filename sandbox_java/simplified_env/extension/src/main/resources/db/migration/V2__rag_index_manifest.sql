-- Completion marker for the immutable, explicitly imported RAG index.
CREATE TABLE rag.index_manifest (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    format_version INTEGER NOT NULL,
    manifest JSONB NOT NULL
);
