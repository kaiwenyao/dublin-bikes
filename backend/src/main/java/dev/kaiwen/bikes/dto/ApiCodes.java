package dev.kaiwen.bikes.dto;

public final class ApiCodes {

    public static final int SUCCESS = 0;
    public static final int STATION_NOT_FOUND = 1;
    public static final int NO_AVAILABLE_ROUTE = 40401;
    public static final int ADDRESS_NOT_RESOLVED = 40402;
    public static final int VALIDATION_ERROR = 40001;
    public static final int AUTH_ERROR = 40101;
    public static final int EMAIL_NOT_VERIFIED = 40301;
    public static final int USERNAME_EXISTS = 40901;
    public static final int EMAIL_EXISTS = 40902;
    public static final int USER_CONFLICT = 40903;
    public static final int WEATHER_ERROR = 50001;
    public static final int GENERIC_ERROR = 50000;

    private ApiCodes() {
    }
}
