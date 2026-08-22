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

    @Query(
            value =
                    "SELECT id, number, available_bikes, available_bike_stands, status, "
                            + "last_update, timestamp, requested_at "
                            + "FROM ( "
                            + "    SELECT a.*, ROW_NUMBER() OVER ( "
                            + "        PARTITION BY a.number "
                            + "        ORDER BY a.timestamp DESC, a.id DESC "
                            + "    ) AS rn "
                            + "    FROM availability a "
                            + ") ranked WHERE ranked.rn = 1",
            nativeQuery = true)
    List<Availability> findLatestPerStation();
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
