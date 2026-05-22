package dev.kaiwen.bikes.dto.response;

public record JourneyResponseDTO(
        JourneyStationLegVO startStation,
        JourneyStationLegVO endStation,
        JourneyCyclingRouteVO cyclingRoute,
        int totalDuration) {}
