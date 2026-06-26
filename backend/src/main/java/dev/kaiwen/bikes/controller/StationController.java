package dev.kaiwen.bikes.controller;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.ApiResponse;
import dev.kaiwen.bikes.dto.response.AvailabilityVO;
import dev.kaiwen.bikes.dto.response.PredictionPointVO;
import dev.kaiwen.bikes.dto.response.StationVO;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.service.PredictionService;
import dev.kaiwen.bikes.service.StationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stations")
@RequiredArgsConstructor
public class StationController {

    private final StationService stationService;
    private final ObjectProvider<PredictionService> predictionService;

    @GetMapping({"", "/"})
    public ApiResponse<List<StationVO>> listStations() {
        return ApiResponse.ok(stationService.listStations());
    }

    @GetMapping("/{number}/availability")
    public ApiResponse<List<AvailabilityVO>> getAvailability(@PathVariable int number) {
        return ApiResponse.ok(stationService.getRecentAvailability(number));
    }

    @GetMapping("/status")
    public ApiResponse<List<AvailabilityVO>> getStatus() {
        return ApiResponse.ok(stationService.getLatestStatusForAllStations());
    }

    @GetMapping("/{number}/prediction")
    public ApiResponse<List<PredictionPointVO>> getPrediction(@PathVariable int number) {
        PredictionService ps = predictionService.getIfAvailable();
        if (ps == null) {
            throw new BusinessException(
                    ApiCodes.GENERIC_ERROR,
                    "Prediction service is not configured",
                    503);
        }
        return ApiResponse.ok(ps.predict(number));
    }
}
