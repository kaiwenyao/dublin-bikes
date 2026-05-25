package dev.kaiwen.bikes.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Validates V2 orphan cleanup and FK against real PostgreSQL (not H2). */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV2MessageStoreMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("dublinbikes");

    @Test
    void v2_deletes_orphan_messages_and_enforces_session_fk() throws Exception {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String user = POSTGRES.getUsername();
        String password = POSTGRES.getPassword();

        Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
                Statement st = conn.createStatement()) {
            st.executeUpdate(
                    """
                    INSERT INTO users (username, email, password_hash, is_active)
                    VALUES ('flyway_test', 'flyway@example.com', 'hash', TRUE)
                    """);
            st.executeUpdate(
                    """
                    INSERT INTO sessions (id, user_id, title)
                    VALUES ('sess-valid', 1, 'Test')
                    """);
            st.executeUpdate(
                    """
                    INSERT INTO message_store (session_id, message)
                    VALUES ('sess-valid', '{"type": "human", "data": {"content": "hi"}}'),
                           ('orphan-session', '{"type": "ai", "data": {"content": "bye"}}')
                    """);
        }

        Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
                Statement st = conn.createStatement()) {
            try (ResultSet orphans =
                    st.executeQuery(
                            """
                            SELECT count(*) AS c FROM message_store
                            WHERE session_id NOT IN (SELECT id FROM sessions)
                            """)) {
                orphans.next();
                assertThat(orphans.getInt("c")).isZero();
            }
            try (ResultSet remaining =
                    st.executeQuery("SELECT count(*) AS c FROM message_store")) {
                remaining.next();
                assertThat(remaining.getInt("c")).isEqualTo(1);
            }
            try (ResultSet fk =
                    st.executeQuery(
                            """
                            SELECT 1 FROM information_schema.table_constraints
                            WHERE constraint_name = 'fk_message_store_session'
                            """)) {
                assertThat(fk.next()).isTrue();
            }
        }
    }
}
