package dev.kaiwen.bikes.dto.response;

public record AuthTokenVO(
        String accessToken,
        String refreshToken,
        int expiresIn,
        String tokenType) {

    public AuthTokenVO(String accessToken, String refreshToken, int expiresIn) {
        this(accessToken, refreshToken, expiresIn, "Bearer");
    }
}
