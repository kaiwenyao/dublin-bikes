package dev.kaiwen.bikes.dto.response;

public record JourneyRouteInfoVO(
        JourneyStationLegVO startStation,
        JourneyStationLegVO endStation,
        JourneyCyclingRouteVO cyclingRoute,
        int totalDuration) {}
