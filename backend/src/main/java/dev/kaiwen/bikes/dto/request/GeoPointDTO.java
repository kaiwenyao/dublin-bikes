package dev.kaiwen.bikes.dto.request;

import jakarta.validation.constraints.NotNull;

public record GeoPointDTO(@NotNull Double lat, @NotNull Double lon) {}
