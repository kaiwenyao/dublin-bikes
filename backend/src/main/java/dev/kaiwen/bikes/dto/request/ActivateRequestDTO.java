package dev.kaiwen.bikes.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ActivateRequestDTO(
        @NotBlank @Size(min = 1, max = 120) String identifier,
        @NotBlank
                @Size(min = 6, max = 6)
                @Pattern(regexp = "^\\d{6}$", message = "code must be a 6-digit string.")
                String code) {}
