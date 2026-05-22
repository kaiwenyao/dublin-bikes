package dev.kaiwen.bikes.dto.request;

public final class UserRequestNormalizer {

    private UserRequestNormalizer() {}

    public static UserRegistrationRequestDTO normalize(UserRegistrationRequestDTO dto) {
        String username = dto.username() == null ? null : dto.username().strip();
        String email = dto.email() == null ? null : dto.email().strip().toLowerCase();
        String avatarUrl = normalizeAvatarUrl(dto.avatarUrl());
        return new UserRegistrationRequestDTO(username, email, dto.password(), avatarUrl);
    }

    public static LoginRequestDTO normalize(LoginRequestDTO dto) {
        return new LoginRequestDTO(
                dto.identifier() == null ? null : dto.identifier().strip(), dto.password());
    }

    public static RefreshTokenRequestDTO normalize(RefreshTokenRequestDTO dto) {
        return new RefreshTokenRequestDTO(
                dto.refreshToken() == null ? null : dto.refreshToken().strip());
    }

    public static ActivateRequestDTO normalize(ActivateRequestDTO dto) {
        return new ActivateRequestDTO(
                dto.identifier() == null ? null : dto.identifier().strip(),
                dto.code() == null ? null : dto.code().strip());
    }

    public static SendVerificationCodeRequestDTO normalize(SendVerificationCodeRequestDTO dto) {
        return new SendVerificationCodeRequestDTO(
                dto.identifier() == null ? null : dto.identifier().strip());
    }

    public static ActivateByTokenRequestDTO normalize(ActivateByTokenRequestDTO dto) {
        return new ActivateByTokenRequestDTO(dto.token() == null ? null : dto.token().strip());
    }

    private static String normalizeAvatarUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return null;
        }
        String trimmed = avatarUrl.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
