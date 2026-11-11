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
     * Latest availability row per station inside a time window.
     *
     * <p>The {@code WHERE a."timestamp" >= :since} predicate is what keeps this bounded: without
     * it the window function ranks every row in the retained history (30 days × 115 stations ×
     * 288 scrapes/day ≈ 1M rows) on every call. Callers must pass a window that is comfortably
     * larger than the scrape interval so a briefly stalled scraper does not blank the response.
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

}
