package dev.kaiwen.bikes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.kaiwen.bikes.client.GoogleMapsClient;
import dev.kaiwen.bikes.client.LatLon;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.request.GeoPointDTO;
import dev.kaiwen.bikes.dto.request.JourneyRequestDTO;
import dev.kaiwen.bikes.dto.response.JourneyPlanResponseVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.model.Availability;
import dev.kaiwen.bikes.model.Station;
import dev.kaiwen.bikes.repository.AvailabilityRepository;
import dev.kaiwen.bikes.repository.StationRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JourneyServiceTest {

    @Mock
    private StationRepository stationRepository;

    @Mock
    private AvailabilityRepository availabilityRepository;

    @Mock
    private GoogleMapsClient googleMapsClient;

    @InjectMocks
    private JourneyService journeyService;

    @Test
    void plan_whenNoEligibleStations_throwsNoRoute() {
        when(availabilityRepository.findLatestPerStationSince(any())).thenReturn(List.of());

        JourneyRequestDTO request =
                new JourneyRequestDTO(null, null, new GeoPointDTO(53.34, -6.26), new GeoPointDTO(53.33, -6.25));

        assertThatThrownBy(() -> journeyService.plan(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.NO_AVAILABLE_ROUTE);
                            assertThat(be.getStatus()).isEqualTo(404);
                        });
    }

    @Test
    void plan_picksMinimumTotalDurationAcrossMatrix() {
        Station startStation = station(1, 53.340, -6.260);
        Station endStation = station(2, 53.345, -6.255);
        Station sameStation = station(3, 53.350, -6.250);

        Availability startAvail = availability(startStation, 5, 10);
        Availability endAvail = availability(endStation, 2, 8);
        Availability sameAvail = availability(sameStation, 4, 6);

        when(stationRepository.findAllByOrderByNumberAsc())
                .thenReturn(List.of(startStation, endStation, sameStation));
        when(availabilityRepository.findLatestPerStationSince(any()))
                .thenReturn(List.of(startAvail, endAvail, sameAvail));

        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("walking")))
                .thenAnswer(
                        invocation -> {
                            List<LatLon> origins = invocation.getArgument(0);
                            List<LatLon> destinations = invocation.getArgument(1);
                            if (origins.size() == 1 && destinations.size() == 3) {
                                return new int[][] {{100, 50, 200}};
                            }
                            if (origins.size() == 3 && destinations.size() == 1) {
                                return new int[][] {{80}, {40}, {90}};
                            }
                            return new int[0][0];
                        });

        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("bicycling")))
                .thenReturn(new int[][] {{300, 400, 500}, {200, 250, 600}, {700, 100, 800}});

        JourneyRequestDTO request =
                new JourneyRequestDTO(null, null, new GeoPointDTO(53.34, -6.26), new GeoPointDTO(53.33, -6.25));

        JourneyPlanResponseVO response = journeyService.plan(request);

        assertThat(response.routeInfo().startStation().number()).isEqualTo(3);
        assertThat(response.routeInfo().endStation().number()).isEqualTo(1);
        assertThat(response.routeInfo().cyclingRoute().cyclingTime()).isEqualTo(100);
        assertThat(response.routeInfo().totalDuration()).isEqualTo(340);
        assertThat(response.searchContext().startResolved().lat()).isEqualTo(53.34);
        assertThat(response.routeInfo().startStation().coords().lat()).isCloseTo(53.35, within(1e-4));
    }


    @Test
    void plan_whenOnlySameStationPair_throwsNoRoute() {
        Station only = station(7, 53.340, -6.260);
        Availability avail = availability(only, 3, 5);

        when(stationRepository.findAllByOrderByNumberAsc()).thenReturn(List.of(only));
        when(availabilityRepository.findLatestPerStationSince(any()))
                .thenReturn(List.of(avail));

        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("walking")))
                .thenReturn(new int[][] {{60}});
        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("bicycling")))
                .thenReturn(new int[][] {{GoogleMapsClient.UNREACHABLE_DURATION}});

        JourneyRequestDTO request =
                new JourneyRequestDTO(null, null, new GeoPointDTO(53.34, -6.26), new GeoPointDTO(53.33, -6.25));

        assertThatThrownBy(() -> journeyService.plan(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.NO_AVAILABLE_ROUTE);
                            assertThat(be.getStatus()).isEqualTo(404);
                        });
    }

    @Test
    void plan_whenAllCycleLegsUnreachable_throwsNoRoute() {
        Station startStation = station(1, 53.340, -6.260);
        Station endStation = station(2, 53.345, -6.255);
        Availability startAvail = availability(startStation, 5, 10);
        Availability endAvail = availability(endStation, 2, 8);

        when(stationRepository.findAllByOrderByNumberAsc()).thenReturn(List.of(startStation, endStation));
        when(availabilityRepository.findLatestPerStationSince(any()))
                .thenReturn(List.of(startAvail, endAvail));

        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("walking")))
                .thenAnswer(
                        invocation -> {
                            List<LatLon> origins = invocation.getArgument(0);
                            List<LatLon> destinations = invocation.getArgument(1);
                            if (origins.size() == 1 && destinations.size() == 2) {
                                return new int[][] {{100, 120}};
                            }
                            if (origins.size() == 2 && destinations.size() == 1) {
                                return new int[][] {{80}, {90}};
                            }
                            return new int[0][0];
                        });

        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("bicycling")))
                .thenReturn(
                        new int[][] {
                            {GoogleMapsClient.UNREACHABLE_DURATION, GoogleMapsClient.UNREACHABLE_DURATION},
                            {GoogleMapsClient.UNREACHABLE_DURATION, GoogleMapsClient.UNREACHABLE_DURATION}
                        });

        JourneyRequestDTO request =
                new JourneyRequestDTO(null, null, new GeoPointDTO(53.34, -6.26), new GeoPointDTO(53.33, -6.25));

        assertThatThrownBy(() -> journeyService.plan(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.NO_AVAILABLE_ROUTE);
                        });
    }

    @Test
    void plan_withAddresses_resolvesBothViaGeocode() {
        Station startStation = station(1, 53.340, -6.260);
        Station endStation = station(2, 53.345, -6.255);

        when(googleMapsClient.geocode("A St")).thenReturn(new LatLon(53.34, -6.26));
        when(googleMapsClient.geocode("B St")).thenReturn(new LatLon(53.33, -6.25));
        when(stationRepository.findAllByOrderByNumberAsc())
                .thenReturn(List.of(startStation, endStation));
        when(availabilityRepository.findLatestPerStationSince(any()))
                .thenReturn(
                        List.of(availability(startStation, 5, 10), availability(endStation, 2, 8)));
        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("walking")))
                .thenAnswer(
                        invocation -> {
                            List<LatLon> origins = invocation.getArgument(0);
                            List<LatLon> destinations = invocation.getArgument(1);
                            if (origins.size() == 1 && destinations.size() == 2) {
                                return new int[][] {{100, 120}};
                            }
                            if (origins.size() == 2 && destinations.size() == 1) {
                                return new int[][] {{80}, {90}};
                            }
                            return new int[0][0];
                        });
        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("bicycling")))
                .thenReturn(new int[][] {{300, 100}, {400, 250}});

        JourneyRequestDTO request = new JourneyRequestDTO("A St", "B St", null, null);

        JourneyPlanResponseVO response = journeyService.plan(request);

        assertThat(response.routeInfo().startStation().number()).isEqualTo(1);
        assertThat(response.routeInfo().endStation().number()).isEqualTo(2);
        assertThat(response.searchContext().startResolved().lat()).isEqualTo(53.34);
        assertThat(response.searchContext().endResolved().lon()).isEqualTo(-6.25);
    }

    @Test
    void plan_filtersOutClosedStaleAndOrphanAvailability() {
        Station openStation = station(1, 53.340, -6.260);
        Station endStation = station(2, 53.345, -6.255);
        Station closedStation = station(3, 53.350, -6.250);
        Station staleStation = station(4, 53.351, -6.251);
        Station emptyStation = station(5, 53.352, -6.252);

        Availability openAvail = availability(openStation, 5, 10);
        Availability endAvail = availability(endStation, 2, 8);
        Availability closedAvail = availability(closedStation, 9, 9);
        closedAvail.setStatus("CLOSED");
        Availability staleAvail = availability(staleStation, 9, 9);
        staleAvail.setTimestamp(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(45));
        // 无车也无桩：起点、终点候选都应把它过滤掉
        Availability emptyAvail = availability(emptyStation, 0, 0);
        // 可用性记录指向 station 表里没有的站点编号，应被过滤
        Availability orphanAvail = new Availability();
        orphanAvail.setNumber(999);
        orphanAvail.setAvailableBikes(9);
        orphanAvail.setAvailableBikeStands(9);
        orphanAvail.setStatus("OPEN");
        orphanAvail.setTimestamp(LocalDateTime.now(ZoneOffset.UTC));

        when(stationRepository.findAllByOrderByNumberAsc())
                .thenReturn(List.of(openStation, endStation, closedStation, staleStation, emptyStation));
        when(availabilityRepository.findLatestPerStationSince(any()))
                .thenReturn(
                        List.of(openAvail, endAvail, closedAvail, staleAvail, emptyAvail, orphanAvail));
        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("walking")))
                .thenAnswer(
                        invocation -> {
                            List<LatLon> origins = invocation.getArgument(0);
                            List<LatLon> destinations = invocation.getArgument(1);
                            if (origins.size() == 1 && destinations.size() == 2) {
                                return new int[][] {{100, 120}};
                            }
                            if (origins.size() == 2 && destinations.size() == 1) {
                                return new int[][] {{80}, {90}};
                            }
                            return new int[0][0];
                        });
        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("bicycling")))
                .thenReturn(new int[][] {{300, 100}, {400, 250}});

        JourneyRequestDTO request =
                new JourneyRequestDTO(null, null, new GeoPointDTO(53.34, -6.26), new GeoPointDTO(53.33, -6.25));

        JourneyPlanResponseVO response = journeyService.plan(request);

        assertThat(response.routeInfo().startStation().number()).isEqualTo(1);
        assertThat(response.routeInfo().endStation().number()).isEqualTo(2);
    }

    @Test
    void plan_walkMatrixEmpty_throwsNoRoute() {
        Station startStation = station(1, 53.340, -6.260);
        Station endStation = station(2, 53.345, -6.255);

        when(stationRepository.findAllByOrderByNumberAsc())
                .thenReturn(List.of(startStation, endStation));
        when(availabilityRepository.findLatestPerStationSince(any()))
                .thenReturn(
                        List.of(availability(startStation, 5, 10), availability(endStation, 2, 8)));
        // Google 步行矩阵整体不可用 → rankByWalking 返回空候选 → noRoute
        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("walking")))
                .thenReturn(new int[0][0]);

        JourneyRequestDTO request =
                new JourneyRequestDTO(null, null, new GeoPointDTO(53.34, -6.26), new GeoPointDTO(53.33, -6.25));

        assertThatThrownBy(() -> journeyService.plan(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getCode())
                                        .isEqualTo(ApiCodes.NO_AVAILABLE_ROUTE));
    }

    @Test
    void plan_walkFromEndMatrixMissingRows_treatedAsUnreachable() {
        Station startStation = station(1, 53.340, -6.260);
        Station endStation = station(2, 53.345, -6.255);

        when(stationRepository.findAllByOrderByNumberAsc())
                .thenReturn(List.of(startStation, endStation));
        when(availabilityRepository.findLatestPerStationSince(any()))
                .thenReturn(
                        List.of(availability(startStation, 5, 10), availability(endStation, 2, 8)));
        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("walking")))
                .thenAnswer(
                        invocation -> {
                            List<LatLon> origins = invocation.getArgument(0);
                            List<LatLon> destinations = invocation.getArgument(1);
                            if (origins.size() == 1 && destinations.size() == 2) {
                                return new int[][] {{100, 120}};
                            }
                            // 终点站到目的地的步行矩阵只返回一行：第二行缺失按 UNREACHABLE 处理
                            if (origins.size() == 2 && destinations.size() == 1) {
                                return new int[][] {{80}};
                            }
                            return new int[0][0];
                        });
        when(googleMapsClient.distanceMatrix(anyList(), anyList(), eq("bicycling")))
                .thenReturn(new int[][] {{300, 400}, {200, 250}});

        JourneyRequestDTO request =
                new JourneyRequestDTO(null, null, new GeoPointDTO(53.34, -6.26), new GeoPointDTO(53.33, -6.25));

        JourneyPlanResponseVO response = journeyService.plan(request);

        // 步行矩阵缺行使终点下标 1 不可达，唯一可行组合为 start=站点2 → end=站点1
        assertThat(response.routeInfo().startStation().number()).isEqualTo(2);
        assertThat(response.routeInfo().endStation().number()).isEqualTo(1);
    }

    private static Station station(int number, double lat, double lon) {
        Station station = new Station();
        station.setNumber(number);
        station.setContractName("dublin");
        station.setName("Station " + number);
        station.setAddress("Address " + number);
        station.setLatitude((float) lat);
        station.setLongitude((float) lon);
        station.setBanking(true);
        station.setBonus(false);
        station.setBikeStands(40);
        return station;
    }

    private static Availability availability(Station station, int bikes, int stands) {
        Availability availability = new Availability();
        availability.setNumber(station.getNumber());
        availability.setAvailableBikes(bikes);
        availability.setAvailableBikeStands(stands);
        availability.setStatus("OPEN");
        availability.setLastUpdate(1L);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        availability.setTimestamp(now);
        availability.setRequestedAt(now);
        return availability;
    }
}
