package dev.kaiwen.bikes.client;

public record LatLon(double lat, double lon) {

    public String toGoogleParam() {
        return lat + "," + lon;
    }
}
