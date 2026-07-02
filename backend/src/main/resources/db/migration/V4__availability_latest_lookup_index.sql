-- Composite index so the chat-service nearest-station LATERAL lookup
-- (latest availability row per station) is an index-only descent instead
-- of scanning each station's full availability history.
-- CONCURRENTLY avoids blocking writes on the ever-growing availability table
-- while the index is being built; Flyway auto-detects this as a
-- non-transactional PostgreSQL statement.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_availability_number_ts_id
    ON availability (number, "timestamp" DESC, id DESC);
