package dev.kaiwen.bikes.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserRegistrationRequestDTO(
        @NotBlank @Size(min = 3, max = 64)
                @Pattern(
                        regexp = "^[A-Za-z0-9_.-]+$",
                        message = "username can only contain letters, numbers, '_', '-' and '.'.")
                String username,
        @NotBlank @Size(max = 120)
                @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "email format is invalid.")
                String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 255) String avatarUrl) {}
