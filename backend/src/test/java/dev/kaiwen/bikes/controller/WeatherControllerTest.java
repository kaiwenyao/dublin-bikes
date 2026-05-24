package dev.kaiwen.bikes.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.WeatherConditionVO;
import dev.kaiwen.bikes.dto.response.WeatherCurrentVO;
import dev.kaiwen.bikes.dto.response.WeatherDataVO;
import dev.kaiwen.bikes.dto.response.WeatherHourlyVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.exception.GlobalExceptionHandler;
import dev.kaiwen.bikes.service.WeatherService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.kaiwen.bikes.support.TestJson;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WeatherControllerTest {

    @Mock
    private WeatherService weatherService;

    @InjectMocks
    private WeatherController weatherController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(weatherController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(TestJson.snakeCaseConverter())
                .build();
    }

    @Test
    void getWeather_returnsOneCallCompatibleShape() throws Exception {
        WeatherDataVO data = new WeatherDataVO();
        data.setCurrent(new WeatherCurrentVO(
                1737367200L,
                4.5f,
                1.2f,
                1019,
                78,
                0.3f,
                90,
                10000,
                5.4f,
                240,
                List.of(new WeatherConditionVO(803, "broken clouds", "04d"))));
        data.setHourly(List.of(new WeatherHourlyVO(1737370800L, 4.7f)));
        when(weatherService.getWeather()).thenReturn(data);

        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.data.current.temp").value(4.5))
                .andExpect(jsonPath("$.data.hourly[0].temp").value(4.7));
    }

    @Test
    void getWeather_whenNoData_returns404() throws Exception {
        when(weatherService.getWeather())
                .thenThrow(new BusinessException(
                        ApiCodes.WEATHER_ERROR, "No weather data available in database", 404));

        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCodes.WEATHER_ERROR))
                .andExpect(jsonPath("$.msg").value("No weather data available in database"));
    }
}
