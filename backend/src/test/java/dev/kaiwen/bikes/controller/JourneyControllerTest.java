package dev.kaiwen.bikes.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.GeoCoordinateVO;
import dev.kaiwen.bikes.dto.response.JourneyCyclingRouteVO;
import dev.kaiwen.bikes.dto.response.JourneyPlanResponseVO;
import dev.kaiwen.bikes.dto.response.JourneyRouteInfoVO;
import dev.kaiwen.bikes.dto.response.JourneySearchContextVO;
import dev.kaiwen.bikes.dto.response.JourneyStationLegVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.exception.GlobalExceptionHandler;
import dev.kaiwen.bikes.service.JourneyService;
import dev.kaiwen.bikes.support.TestJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class JourneyControllerTest {

    @Mock
    private JourneyService journeyService;

    @InjectMocks
    private JourneyController journeyController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(journeyController)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setMessageConverters(TestJson.snakeCaseConverter())
                        .build();
    }

    @Test
    void plan_returnsNestedRoutePayload() throws Exception {
        JourneyPlanResponseVO response =
                new JourneyPlanResponseVO(
                        new JourneyRouteInfoVO(
                                new JourneyStationLegVO(
                                        1,
                                        "A",
                                        "Addr A",
                                        new GeoCoordinateVO(53.34, -6.26),
                                        120,
                                        5,
                                        null),
                                new JourneyStationLegVO(
                                        2,
                                        "B",
                                        "Addr B",
                                        new GeoCoordinateVO(53.33, -6.25),
                                        180,
                                        null,
                                        7),
                                new JourneyCyclingRouteVO(540),
                                840),
                        new JourneySearchContextVO(
                                new GeoCoordinateVO(53.34, -6.26), new GeoCoordinateVO(53.33, -6.25)));

        when(journeyService.plan(any())).thenReturn(response);

        mockMvc.perform(
                        post("/api/journey/plan")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"start\":{\"lat\":53.34,\"lon\":-6.26},\"end\":{\"lat\":53.33,\"lon\":-6.25}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.data.route_info.total_duration").value(840))
                .andExpect(jsonPath("$.data.search_context.start_resolved.lat").value(53.34))
                .andExpect(jsonPath("$.data.route_info.start_station.walking_time").value(120));
    }

    @Test
    void plan_whenNoRoute_returns404Envelope() throws Exception {
        when(journeyService.plan(any()))
                .thenThrow(new BusinessException(ApiCodes.NO_AVAILABLE_ROUTE, "no available route", 404));

        mockMvc.perform(
                        post("/api/journey/plan")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"start\":{\"lat\":53.34,\"lon\":-6.26},\"end\":{\"lat\":53.33,\"lon\":-6.25}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCodes.NO_AVAILABLE_ROUTE))
                .andExpect(jsonPath("$.msg").value("no available route"));
    }
}
