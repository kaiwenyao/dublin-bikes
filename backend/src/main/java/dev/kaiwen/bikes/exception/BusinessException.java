package dev.kaiwen.bikes.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final int status;

    public BusinessException(int code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
