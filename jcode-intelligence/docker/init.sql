CREATE EXTENSION IF NOT EXISTS vector;

-- Idempotent index initialization for existing container volumes
-- (Fresh containers will also be indexed upon Spring AI schema initialization)
DO $$
BEGIN
    IF to_regclass('public.vector_store') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_vector_store_repo_chunk_id
            ON vector_store ((metadata->>'repositoryId'), (metadata->>'chunkId'));

        CREATE INDEX IF NOT EXISTS idx_vector_store_repo_type
            ON vector_store ((metadata->>'repositoryId'), (metadata->>'type'));

        CREATE INDEX IF NOT EXISTS idx_vector_store_repo_class_name
            ON vector_store ((metadata->>'repositoryId'), (metadata->>'className'));

        CREATE INDEX IF NOT EXISTS idx_vector_store_repo_element_name
            ON vector_store ((metadata->>'repositoryId'), (metadata->>'elementName'));

        CREATE INDEX IF NOT EXISTS idx_vector_store_repo_method_name
            ON vector_store ((metadata->>'repositoryId'), (metadata->>'methodName'));

        CREATE INDEX IF NOT EXISTS idx_vector_store_repo_package_name
            ON vector_store ((metadata->>'repositoryId'), (metadata->>'packageName'));

        CREATE INDEX IF NOT EXISTS idx_vector_store_repo_element_pattern
            ON vector_store ((metadata->>'repositoryId'), (metadata->>'elementName') varchar_pattern_ops);
    END IF;
END $$;
