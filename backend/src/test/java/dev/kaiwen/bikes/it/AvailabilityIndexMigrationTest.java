package dev.kaiwen.bikes.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V5 builds {@code ix_availability_requested_at} with {@code CREATE INDEX CONCURRENTLY}.
 *
 * <p>That statement can leave an INVALID index behind when it is interrupted, and
 * {@code IF NOT EXISTS} only checks the index name — a naive re-run would report success while
 * the retention purge and {@code /api/stations/status} kept scanning. V5 therefore drops the
 * index before (re)creating it. This test produces a real {@code indisvalid = false} index under
 * the target name and then applies the V5 statements, so removing the protective drop makes it
 * fail.
 */
class AvailabilityIndexMigrationTest extends IntegrationTestBase {

    private static final String INDEX_NAME = "ix_availability_requested_at";
    private static final String PROBE_TABLE = "availability_index_probe";

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void aLeftoverInvalidIndexIsDroppedAndRebuiltValid() {
        try {
            createInvalidLeftoverIndex();

            // The leftover really is an invalid index under the target name.
            assertThat(indexCount()).isEqualTo(1);
            assertThat(indexIsValid()).isFalse();

            // V5 statements: drop-then-create must rebuild it on availability, valid.
            applyV5IndexStatements();

            assertThat(indexCount()).isEqualTo(1);
            assertThat(indexIsValid()).isTrue();
            assertThat(indexTable()).isEqualTo("availability");
        } finally {
            // Restore the migrated state even if an assertion above failed.
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
            applyV5IndexStatements();
        }
    }

    /**
     * A concurrent UNIQUE build on duplicate values fails validation and leaves the index behind
     * as {@code indisvalid = false} — the same shape as an interrupted {@code CREATE INDEX
     * CONCURRENTLY}, but deterministic.
     */
    private void createInvalidLeftoverIndex() {
        jdbcTemplate.execute("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX_NAME);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbcTemplate.execute("CREATE TABLE " + PROBE_TABLE + " (x integer)");
        jdbcTemplate.execute("INSERT INTO " + PROBE_TABLE + " (x) VALUES (1), (1)");

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.execute(
                                        "CREATE UNIQUE INDEX CONCURRENTLY "
                                                + INDEX_NAME
                                                + " ON "
                                                + PROBE_TABLE
                                                + " (x)"))
                .isInstanceOf(DataAccessException.class);
    }

    private void applyV5IndexStatements() {
        jdbcTemplate.execute("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX_NAME);
        jdbcTemplate.execute(
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS "
                        + INDEX_NAME
                        + " ON availability (requested_at)");
    }

    private int indexCount() {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                                + " WHERE c.relname = ?",
                        Integer.class,
                        INDEX_NAME);
        return count == null ? 0 : count;
    }

    private boolean indexIsValid() {
        Boolean valid =
                jdbcTemplate.queryForObject(
                        "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                                + " WHERE c.relname = ?",
                        Boolean.class,
                        INDEX_NAME);
        return Boolean.TRUE.equals(valid);
    }

    private String indexTable() {
        return jdbcTemplate.queryForObject(
                "SELECT t.relname FROM pg_index i"
                        + " JOIN pg_class c ON c.oid = i.indexrelid"
                        + " JOIN pg_class t ON t.oid = i.indrelid"
                        + " WHERE c.relname = ?",
                String.class,
                INDEX_NAME);
    }
}
