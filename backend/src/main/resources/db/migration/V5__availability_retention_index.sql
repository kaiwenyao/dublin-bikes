-- Index hygiene for the ever-growing availability table.
--
-- 1. Retention purge (scraper/retention.py) deletes rows with
--    `requested_at < cutoff` in batches of 5 000. There was no index on
--    requested_at, so PostgreSQL planned a sequential scan for every batch:
--    a backlog (first start, or a long scraper outage) costs O(N^2) heap
--    visits while the scrape loop waits. This index makes each batch lookup a
--    range scan on the retention boundary. /api/stations/status uses it too,
--    for its scrape-time window.
--
-- 2. ix_availability_number (V1) is the leftmost prefix of
--    ix_availability_number_ts_id (number, "timestamp" DESC, id DESC) added in
--    V4. Every `WHERE number = ?` lookup and the FK check on
--    availability.number are served by the composite index, so the
--    single-column index only adds write amplification on the scrape path
--    (~33k inserts/day). Drop it.
--
-- CONCURRENTLY keeps these statements from blocking scraper writes; Flyway
-- auto-detects them as non-transactional PostgreSQL statements (see
-- spring.flyway.postgresql.transactional-lock: false in application.yaml).

DROP INDEX CONCURRENTLY IF EXISTS ix_availability_number;

-- Drop before creating: an interrupted CREATE INDEX CONCURRENTLY can leave an
-- INVALID index under this name, and IF NOT EXISTS only checks the name — a
-- re-run would report success while the retention purge and /status keep
-- scanning. This migration has not shipped yet, so rewriting it is safe; a
-- deployed environment would need a follow-up repair migration that checks
-- pg_index.indisvalid.
DROP INDEX CONCURRENTLY IF EXISTS ix_availability_requested_at;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_availability_requested_at
    ON availability (requested_at);
