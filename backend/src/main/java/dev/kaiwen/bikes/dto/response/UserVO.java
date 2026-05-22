package dev.kaiwen.bikes.dto.response;

public record UserVO(
        int id,
        String username,
        String email,
        String avatarUrl,
        boolean isActive,
        String createdAt) {}
