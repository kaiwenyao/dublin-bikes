package dev.kaiwen.bikes.dto.response;

public record SendVerificationCodeMessageVO(String message) {

    public static SendVerificationCodeMessageVO defaults() {
        return new SendVerificationCodeMessageVO("verification code sent");
    }
}
