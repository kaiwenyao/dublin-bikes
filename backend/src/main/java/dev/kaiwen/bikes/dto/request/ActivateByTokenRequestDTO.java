package dev.kaiwen.bikes.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ActivateByTokenRequestDTO(@NotBlank String token) {}
