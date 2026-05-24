package dev.kaiwen.bikes.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.AvailabilityVO;
import dev.kaiwen.bikes.dto.response.StationVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.exception.GlobalExceptionHandler;
import dev.kaiwen.bikes.service.StationService;
import dev.kaiwen.bikes.support.TestJson;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class StationControllerTest {

    @Mock
    private StationService stationService;

    @InjectMocks
    private StationController stationController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(stationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(TestJson.snakeCaseConverter())
                .build();
    }

    @Test
    void listStations_returnsOkEnvelope() throws Exception {
        StationVO vo = new StationVO(1, "dublin", "A", "Addr", 53.3f, -6.2f, true, false, 40);
        when(stationService.listStations()).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/stations/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.data[0].number").value(1))
                .andExpect(jsonPath("$.data[0].contract_name").value("dublin"));
    }

    @Test
    void getAvailability_whenStationMissing_returns404() throws Exception {
        when(stationService.getRecentAvailability(99))
                .thenThrow(new BusinessException(ApiCodes.STATION_NOT_FOUND, "station not found", 404));

        mockMvc.perform(get("/api/stations/99/availability"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCodes.STATION_NOT_FOUND))
                .andExpect(jsonPath("$.msg").value("station not found"));
    }

    @Test
    void getStatus_returnsFlatAvailabilityList() throws Exception {
        AvailabilityVO vo = new AvailabilityVO(1, 5, 10, "OPEN", 1L, "2025-01-20T10:00:00", "2025-01-20T10:00:05");
        when(stationService.getLatestStatusForAllStations()).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/stations/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.data[0].number").value(1))
                .andExpect(jsonPath("$.data[0].available_bikes").value(5));
    }
}
