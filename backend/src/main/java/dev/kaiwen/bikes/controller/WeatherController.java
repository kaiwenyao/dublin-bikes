package dev.kaiwen.bikes.controller;

import dev.kaiwen.bikes.dto.ApiResponse;
import dev.kaiwen.bikes.dto.response.WeatherDataVO;
import dev.kaiwen.bikes.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    @GetMapping
    public ApiResponse<WeatherDataVO> getWeather() {
        return ApiResponse.ok(weatherService.getWeather());
    }
}
