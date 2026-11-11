package dev.kaiwen.bikes.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V5 builds {@code ix_availability_requested_at} with {@code CREATE INDEX CONCURRENTLY}.
 *
 * <p>That statement can leave an INVALID index behind when it is interrupted, and
 * {@code IF NOT EXISTS} only checks the index name — a naive re-run would report success while
 * the retention purge and {@code /api/stations/status} kept scanning. V5 therefore drops the
 * index before (re)creating it; these statements re-applied to a real PostgreSQL must end with
 * exactly one valid index.
 */
class AvailabilityIndexMigrationTest extends IntegrationTestBase {

    private static final String INDEX_NAME = "ix_availability_requested_at";

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void reapplyingTheV5StatementsLeavesOneValidIndex() {
        applyV5IndexStatements();
        // Second pass simulates a migration re-run after a partial or failed previous build.
        applyV5IndexStatements();

        Integer total =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                                + " WHERE c.relname = ?",
                        Integer.class,
                        INDEX_NAME);
        Integer valid =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                                + " WHERE c.relname = ? AND i.indisvalid",
                        Integer.class,
                        INDEX_NAME);

        assertThat(total).isEqualTo(1);
        assertThat(valid).isEqualTo(1);
    }

    private void applyV5IndexStatements() {
        jdbcTemplate.execute("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX_NAME);
        jdbcTemplate.execute(
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS "
                        + INDEX_NAME
                        + " ON availability (requested_at)");
    }
}
