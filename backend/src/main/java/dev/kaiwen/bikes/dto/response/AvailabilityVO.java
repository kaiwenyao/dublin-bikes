package dev.kaiwen.bikes.dto.response;

public record AvailabilityVO(
        int number,
        int availableBikes,
        int availableBikeStands,
        String status,
        long lastUpdate,
        String timestamp,
        String requestedAt) {}
