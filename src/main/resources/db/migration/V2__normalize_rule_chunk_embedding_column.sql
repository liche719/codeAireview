DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema()
          AND c.relname = 'rule_chunk'
          AND a.attname = 'embedding'
          AND a.attnum > 0
          AND NOT a.attisdropped
          AND format_type(a.atttypid, a.atttypmod) <> 'vector'
    ) THEN
        ALTER TABLE rule_chunk
            ALTER COLUMN embedding TYPE vector
            USING embedding::vector;
    END IF;
END $$;
