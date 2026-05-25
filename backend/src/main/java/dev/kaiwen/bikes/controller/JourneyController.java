package dev.kaiwen.bikes.controller;

import dev.kaiwen.bikes.dto.ApiResponse;
import dev.kaiwen.bikes.dto.request.JourneyRequestDTO;
import dev.kaiwen.bikes.dto.request.JourneyRequestNormalizer;
import dev.kaiwen.bikes.dto.response.JourneyPlanResponseVO;
import dev.kaiwen.bikes.service.JourneyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/journey")
@RequiredArgsConstructor
public class JourneyController {

    private final JourneyService journeyService;

    @PostMapping("/plan")
    public ApiResponse<JourneyPlanResponseVO> plan(@RequestBody JourneyRequestDTO request) {
        return ApiResponse.ok(journeyService.plan(JourneyRequestNormalizer.normalize(request)));
    }
}
