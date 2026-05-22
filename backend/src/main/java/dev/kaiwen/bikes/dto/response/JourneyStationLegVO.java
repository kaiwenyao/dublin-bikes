package dev.kaiwen.bikes.dto.response;

public record JourneyStationLegVO(
        int number,
        String name,
        int walkingTime,
        Integer availableBikes,
        Integer availableBikeStands) {}
