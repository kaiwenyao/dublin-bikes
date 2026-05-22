package dev.kaiwen.bikes.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequestDTO(
        @NotBlank @Size(min = 1, max = 120) String identifier,
        @NotBlank String password) {}
