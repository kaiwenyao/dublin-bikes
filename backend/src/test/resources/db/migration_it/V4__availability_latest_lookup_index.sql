-- Composite index so the chat-service nearest-station LATERAL lookup
-- (latest availability row per station) is an index-only descent instead
-- of scanning each station's full availability history.
--
-- IT VARIANT: drops CONCURRENTLY. In integration tests the availability table
-- is empty and the build is single-tenant, so there is no write traffic to
-- avoid blocking. CONCURRENTLY cannot run inside a transaction and causes
-- Flyway's JDBC connection to hang on Testcontainers PostgreSQL (the driver
-- sends the statement but PostgreSQL never returns a result — likely a
-- server-prepared-statement / extended-protocol interaction). Plain CREATE
-- INDEX runs inside the migration transaction and is functionally identical
-- for test correctness.
CREATE INDEX IF NOT EXISTS ix_availability_number_ts_id
    ON availability (number, "timestamp" DESC, id DESC);
