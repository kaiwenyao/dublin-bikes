package dev.kaiwen.bikes.client;

/** Signals a retryable Google Maps API / transport failure (not a client input error). */
public class GoogleMapsTransientException extends RuntimeException {

    public GoogleMapsTransientException(String message) {
        super(message);
    }
}
