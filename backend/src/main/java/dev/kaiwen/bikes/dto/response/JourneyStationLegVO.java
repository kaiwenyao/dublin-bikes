package dev.kaiwen.bikes.dto.response;

public record JourneyStationLegVO(
        int number,
        String name,
        String address,
        GeoCoordinateVO coords,
        int walkingTime,
        Integer availableBikes,
        Integer availableBikeStands) {}
