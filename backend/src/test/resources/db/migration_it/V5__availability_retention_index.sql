-- Index hygiene for the ever-growing availability table (retention purge
-- lookup + redundant single-column index removal). See the production
-- migration db/migration/V5 for the full rationale.
--
-- IT VARIANT: drops CONCURRENTLY. The availability table is empty in
-- integration tests and the build is single-tenant, so there is no write
-- traffic to avoid blocking. CONCURRENTLY cannot run inside a transaction and
-- hangs Flyway's JDBC connection on Testcontainers PostgreSQL (same reason as
-- the V4 IT variant).
DROP INDEX IF EXISTS ix_availability_number;

-- Drop first so a previously created (or partially built) index of the same
-- name cannot make IF NOT EXISTS skip the rebuild.
DROP INDEX IF EXISTS ix_availability_requested_at;

CREATE INDEX IF NOT EXISTS ix_availability_requested_at
    ON availability (requested_at);
