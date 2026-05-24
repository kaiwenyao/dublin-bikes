package dev.kaiwen.bikes.security;

public record JwtTokenClaims(int userId, int tokenVersion, String type) {}
