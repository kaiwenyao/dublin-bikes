package dev.kaiwen.bikes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.AvailabilityVO;
import dev.kaiwen.bikes.dto.response.StationVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.mapper.StationMapper;
import dev.kaiwen.bikes.model.Availability;
import dev.kaiwen.bikes.model.Station;
import dev.kaiwen.bikes.repository.AvailabilityRepository;
import dev.kaiwen.bikes.repository.StationRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StationServiceTest {

    @Mock
    private StationRepository stationRepository;

    @Mock
    private AvailabilityRepository availabilityRepository;

    @Mock
    private StationMapper stationMapper;

    @InjectMocks
    private StationService stationService;

    @Test
    void listStations_returnsMappedList() {
        Station station = new Station();
        station.setNumber(1);
        StationVO vo = new StationVO(1, "dublin", "A", "Addr", 53.3f, -6.2f, true, false, 40);
        when(stationRepository.findAllByOrderByNumberAsc()).thenReturn(List.of(station));
        when(stationMapper.toVOList(List.of(station))).thenReturn(List.of(vo));

        assertThat(stationService.listStations()).containsExactly(vo);
    }

    @Test
    void getRecentAvailability_whenStationMissing_throwsNotFound() {
        when(stationRepository.existsById(99)).thenReturn(false);

        assertThatThrownBy(() -> stationService.getRecentAvailability(99))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ApiCodes.STATION_NOT_FOUND);
                    assertThat(be.getStatus()).isEqualTo(404);
                    assertThat(be.getMessage()).isEqualTo("station not found");
                });
    }

    @Test
    void getRecentAvailability_returnsMappedRows() {
        Availability row = new Availability();
        row.setNumber(1);
        AvailabilityVO vo = new AvailabilityVO(1, 5, 10, "OPEN", 1L, "2025-01-20T10:00:00", "2025-01-20T10:00:05");
        when(stationRepository.existsById(1)).thenReturn(true);
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(availabilityRepository.findRecent(eq(1), sinceCaptor.capture())).thenReturn(List.of(row));
        when(stationMapper.toAvailabilityVOList(List.of(row))).thenReturn(List.of(vo));

        assertThat(stationService.getRecentAvailability(1)).containsExactly(vo);

        LocalDateTime expectedSince = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        assertThat(sinceCaptor.getValue())
                .isCloseTo(expectedSince, within(2, ChronoUnit.SECONDS));
    }

    @Test
    void getLatestStatusForAllStations_returnsMappedRows() {
        Availability row = new Availability();
        AvailabilityVO vo = new AvailabilityVO(1, 5, 10, "OPEN", 1L, "2025-01-20T10:00:00", "2025-01-20T10:00:05");
        when(availabilityRepository.findLatestPerStation()).thenReturn(List.of(row));
        when(stationMapper.toAvailabilityVOList(List.of(row))).thenReturn(List.of(vo));

        assertThat(stationService.getLatestStatusForAllStations()).containsExactly(vo);
    }
}
