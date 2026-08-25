package dev.kaiwen.bikes.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaiwen.bikes.model.Availability;
import dev.kaiwen.bikes.model.Station;
import dev.kaiwen.bikes.repository.AvailabilityRepository;
import dev.kaiwen.bikes.repository.StationRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 站点 REST API 集成测试：真实 PostgreSQL + Flyway + JPA/Hibernate。
 *
 * <p>覆盖 {@code /api/stations} 的三个公开接口：
 * <ul>
 *   <li>{@code GET /api/stations} — 列出全部站点</li>
 *   <li>{@code GET /api/stations/{number}/availability} — 最近 24h 可用性历史</li>
 *   <li>{@code GET /api/stations/status} — 所有站点的最新状态</li>
 * </ul>
 *
 * <p>这些接口在 {@code SecurityConfig} 中是 {@code permitAll}，无需认证。
 * 测试数据通过 JPA repository 直接写入真实 PostgreSQL，再经真实 HTTP 请求读取，
 * 验证 Flyway schema、Hibernate 实体映射、Spring Data JPA 查询与 Jackson 序列化
 * 在 PostgreSQL 方言下的完整正确性。
 */
class StationRestIntegrationTest extends IntegrationTestBase {

    @Autowired private StationRepository stationRepository;
    @Autowired private AvailabilityRepository availabilityRepository;

    @BeforeEach
    void cleanDatabase() {
        availabilityRepository.deleteAllInBatch();
        stationRepository.deleteAllInBatch();
    }

    @Test
    void listStations_returnsAllStationsOrderedByNumber() {
        persistStation(42, "Station B", 53.35f, -6.21f);
        persistStation(10, "Station A", 53.34f, -6.26f);

        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/stations", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(0);
        assertThat(body.get("msg")).isEqualTo("ok");
        List<?> data = (List<?>) body.get("data");
        assertThat(data).hasSize(2);
        // ordered by number ascending
        Map<?, ?> first = (Map<?, ?>) data.get(0);
        assertThat(first.get("number")).isEqualTo(10);
        assertThat(first.get("name")).isEqualTo("Station A");
        assertThat(first.get("contract_name")).isEqualTo("dublin");
        Map<?, ?> second = (Map<?, ?>) data.get(1);
        assertThat(second.get("number")).isEqualTo(42);
    }

    @Test
    void getAvailability_returnsRecentSnapshotsForStation() {
        persistStation(5, "Station C", 53.3f, -6.2f);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // recent snapshot (within 24h) — should appear
        persistAvailability(5, now.minusMinutes(10), 3, 10);
        // older snapshot (outside 24h) — should be filtered out
        persistAvailability(5, now.minusDays(2), 20, 0);

        ResponseEntity<Map> resp =
                restTemplate.getForEntity("/api/stations/5/availability", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        List<?> data = (List<?>) body.get("data");
        assertThat(data).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) data.get(0);
        assertThat(row.get("number")).isEqualTo(5);
        assertThat(row.get("available_bikes")).isEqualTo(3);
        assertThat(row.get("available_bike_stands")).isEqualTo(10);
        assertThat(row.get("status")).isEqualTo("OPEN");
    }

    @Test
    void getAvailability_whenStationMissing_returns404() {
        ResponseEntity<Map> resp =
                restTemplate.getForEntity("/api/stations/999/availability", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        // STATION_NOT_FOUND = 1
        assertThat(body.get("code")).isEqualTo(1);
    }

    @Test
    void getStatus_returnsLatestSnapshotPerStation() {
        persistStation(1, "Station X", 53.3f, -6.2f);
        persistStation(2, "Station Y", 53.4f, -6.1f);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // Station 1: two snapshots — the newer one should win
        persistAvailability(1, now.minusHours(2), 5, 10);
        persistAvailability(1, now.minusMinutes(5), 8, 7);
        // Station 2: one snapshot
        persistAvailability(2, now.minusMinutes(3), 12, 3);

        ResponseEntity<Map> resp = restTemplate.getForEntity("/api/stations/status", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        assertThat(body).isNotNull();
        List<?> data = (List<?>) body.get("data");
        assertThat(data).hasSize(2);

        // Each station should appear once with its latest snapshot
        Map<?, ?> s1 = findRowByNumber(data, 1);
        assertThat(s1.get("available_bikes")).isEqualTo(8);
        Map<?, ?> s2 = findRowByNumber(data, 2);
        assertThat(s2.get("available_bikes")).isEqualTo(12);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findRowByNumber(List<?> data, int number) {
        return (Map<String, Object>)
                data.stream()
                        .filter(
                                r ->
                                        ((Number) ((Map<?, ?>) r).get("number")).intValue()
                                                == number)
                        .findFirst()
                        .orElseThrow();
    }

    private void persistStation(int number, String name, float lat, float lon) {
        Station station = new Station();
        station.setNumber(number);
        station.setContractName("dublin");
        station.setName(name);
        station.setAddress("Test Address " + number);
        station.setLatitude(lat);
        station.setLongitude(lon);
        station.setBanking(true);
        station.setBonus(false);
        station.setBikeStands(40);
        stationRepository.saveAndFlush(station);
    }

    private void persistAvailability(int number, LocalDateTime timestamp, int bikes, int stands) {
        Availability availability = new Availability();
        availability.setNumber(number);
        availability.setAvailableBikes(bikes);
        availability.setAvailableBikeStands(stands);
        availability.setStatus("OPEN");
        availability.setLastUpdate(1L);
        availability.setTimestamp(timestamp);
        // requested_at is the scrape time — always <= timestamp (the JCDecaux data time)
        availability.setRequestedAt(timestamp);
        availabilityRepository.saveAndFlush(availability);
    }
}
