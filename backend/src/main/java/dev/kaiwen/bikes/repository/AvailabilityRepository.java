package dev.kaiwen.bikes.repository;

import dev.kaiwen.bikes.model.Availability;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilityRepository extends JpaRepository<Availability, Integer> {

    @Query(
            "SELECT a FROM Availability a "
                    + "WHERE a.number = :number AND a.timestamp >= :since "
                    + "ORDER BY a.timestamp DESC")
    List<Availability> findRecent(@Param("number") int number, @Param("since") LocalDateTime since);

    @Query(
            value =
                    "SELECT a.* FROM availability a "
                            + "INNER JOIN ( "
                            + "    SELECT number, MAX(timestamp) AS max_ts "
                            + "    FROM availability "
                            + "    GROUP BY number "
                            + ") latest "
                            + "ON a.number = latest.number AND a.timestamp = latest.max_ts",
            nativeQuery = true)
    List<Availability> findLatestPerStation();
}
