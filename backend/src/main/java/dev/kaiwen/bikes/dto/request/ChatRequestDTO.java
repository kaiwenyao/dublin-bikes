package dev.kaiwen.bikes.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ChatRequestDTO(@NotBlank String message, String chatId) {}
