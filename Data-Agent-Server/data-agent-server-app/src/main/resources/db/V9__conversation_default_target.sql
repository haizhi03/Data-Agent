-- Existing conversations intentionally remain unspecified. No inferred backfill or FK cascade.
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS default_connection_id BIGINT;
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS default_catalog_name VARCHAR(512);
ALTER TABLE ai_conversation ADD COLUMN IF NOT EXISTS default_schema_name VARCHAR(512);
