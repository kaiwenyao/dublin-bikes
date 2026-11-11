package dev.kaiwen.bikes.repository;

import dev.kaiwen.bikes.model.Availability;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilityRepository extends JpaRepository<Availability, Integer> {

    List<Availability> findByNumberAndTimestampGreaterThanEqualOrderByTimestampDesc(
            int number, LocalDateTime since);

    default List<Availability> findRecent(int number, LocalDateTime since) {
        return findByNumberAndTimestampGreaterThanEqualOrderByTimestampDesc(number, since);
    }

    /**
     * Latest availability row per station inside a window of <em>upstream</em> timestamps.
     *
     * <p>{@code timestamp} is the JCDecaux {@code last_update} (when the data was generated), so
     * this window answers "was this station's own data refreshed recently?". Used by journey
     * planning, which must not offer a station whose upstream data is stale.
     *
     * <p>The {@code WHERE a."timestamp" >= :since} predicate is what keeps the query bounded:
     * without it the window function ranks every row in the retained history (30 days × 115
     * stations × 288 scrapes/day ≈ 1M rows) on every call.
     */
    @Query(
            value =
                    "SELECT id, number, available_bikes, available_bike_stands, status, "
                            + "last_update, timestamp, requested_at "
                            + "FROM ( "
                            + "    SELECT a.*, ROW_NUMBER() OVER ( "
                            + "        PARTITION BY a.number ORDER BY a.timestamp DESC, a.id DESC "
                            + "    ) AS rn "
                            + "    FROM availability a "
                            + "    WHERE a.timestamp >= :since "
                            + ") ranked WHERE ranked.rn = 1",
            nativeQuery = true)
    List<Availability> findLatestPerStationSince(@Param("since") LocalDateTime since);

    /**
     * Latest availability row per station whose latest <em>scrape</em> is inside the window.
     *
     * <p>{@code requested_at} is when the scraper collected the row, whereas {@code timestamp} is
     * the upstream {@code last_update}. A station can keep the same {@code last_update} for days
     * (closed for maintenance, broken dock, upstream freeze) while still being collected every 5
     * minutes. Filtering on {@code timestamp} would drop such a station from {@code /status} even
     * though we have fresh knowledge of it; filtering on {@code requested_at} drops a station only
     * when the scraper really stopped writing for it.
     *
     * <p>Rows are still ranked by {@code timestamp} (with {@code id} as tie-break) so the reported
     * row is the freshest data we hold, even if that data itself is older than the window.
     */
    @Query(
            value =
                    "SELECT id, number, available_bikes, available_bike_stands, status, "
                            + "last_update, timestamp, requested_at "
                            + "FROM ( "
                            + "    SELECT a.*, ROW_NUMBER() OVER ( "
                            + "        PARTITION BY a.number ORDER BY a.timestamp DESC, a.id DESC "
                            + "    ) AS rn "
                            + "    FROM availability a "
                            + "    WHERE a.requested_at >= :since "
                            + ") ranked WHERE ranked.rn = 1",
            nativeQuery = true)
    List<Availability> findLatestPerStationScrapedSince(@Param("since") LocalDateTime since);

}
