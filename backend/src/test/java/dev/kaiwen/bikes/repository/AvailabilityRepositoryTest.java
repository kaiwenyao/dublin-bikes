package dev.kaiwen.bikes.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaiwen.bikes.model.Availability;
import dev.kaiwen.bikes.model.Station;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
class AvailabilityRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AvailabilityRepository availabilityRepository;

    @BeforeEach
    void setUp() {
        persistStation(1, "Station A");
        persistStation(2, "Station B");
    }

    @Test
    void findLatestPerStationSince_ignoresRowsOlderThanWindow() {
        persistAvailability(1, LocalDateTime.parse("2025-01-20T08:00:00"), 1, 10);
        persistAvailability(1, LocalDateTime.parse("2025-01-20T10:00:00"), 5, 8);
        persistAvailability(2, LocalDateTime.parse("2025-01-20T07:00:00"), 9, 9);
        entityManager.flush();
        entityManager.clear();

        List<Availability> latest =
                availabilityRepository.findLatestPerStationSince(
                        LocalDateTime.parse("2025-01-20T09:00:00"));

        assertThat(latest).hasSize(1);
        assertThat(latest.getFirst().getNumber()).isEqualTo(1);
        assertThat(latest.getFirst().getAvailableBikes()).isEqualTo(5);
    }

    @Test
    void findLatestPerStation_returnsOneRowPerStation() {
        persistAvailability(1, LocalDateTime.parse("2025-01-20T09:00:00"), 3, 10);
        persistAvailability(1, LocalDateTime.parse("2025-01-20T10:00:00"), 5, 8);
        persistAvailability(2, LocalDateTime.parse("2025-01-20T10:00:00"), 2, 15);
        entityManager.flush();
        entityManager.clear();

        List<Availability> latest = availabilityRepository.findLatestPerStation();

        assertThat(latest).hasSize(2);
        assertThat(latest)
                .extracting(Availability::getNumber, Availability::getAvailableBikes)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, 5),
                        org.assertj.core.groups.Tuple.tuple(2, 2));
    }

    private void persistStation(int number, String name) {
        Station station = new Station();
        station.setNumber(number);
        station.setContractName("dublin");
        station.setName(name);
        station.setAddress("Addr");
        station.setLatitude(53.3f);
        station.setLongitude(-6.2f);
        station.setBanking(true);
        station.setBonus(false);
        station.setBikeStands(40);
        entityManager.persist(station);
    }

    private void persistAvailability(
            int number, LocalDateTime timestamp, int bikes, int stands) {
        Availability availability = new Availability();
        availability.setNumber(number);
        availability.setAvailableBikes(bikes);
        availability.setAvailableBikeStands(stands);
        availability.setStatus("OPEN");
        availability.setLastUpdate(1L);
        availability.setTimestamp(timestamp);
        availability.setRequestedAt(timestamp.plusSeconds(5));
        entityManager.persist(availability);
    }
}
