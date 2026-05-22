package dev.kaiwen.bikes.dto.request;

public record JourneyRequestDTO(
        String startAddress,
        String endAddress,
        GeoPointDTO start,
        GeoPointDTO end) {}
