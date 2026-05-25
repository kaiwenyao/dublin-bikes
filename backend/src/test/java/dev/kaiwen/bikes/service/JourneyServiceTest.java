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
        when(availabilityRepository.findLatestPerStation()).thenReturn(List.of());

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
        when(availabilityRepository.findLatestPerStation())
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
