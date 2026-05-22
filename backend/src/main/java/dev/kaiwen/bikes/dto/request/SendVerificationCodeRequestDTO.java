package dev.kaiwen.bikes.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendVerificationCodeRequestDTO(@NotBlank @Size(min = 1, max = 120) String identifier) {}
