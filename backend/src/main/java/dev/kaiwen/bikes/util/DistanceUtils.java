package dev.kaiwen.bikes.util;

public final class DistanceUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double WALKING_SPEED_KMH = 5.0;
    private static final double BICYCLING_SPEED_KMH = 14.0;

    private DistanceUtils() {}

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLon / 2)
                                * Math.sin(dLon / 2);
        a = Math.max(0.0, Math.min(1.0, a));
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /** Straight-line duration estimate when Google Distance Matrix is unavailable. */
    public static int estimatedDurationSeconds(double distanceKm, String travelMode) {
        if (distanceKm <= 0.0) {
            return 0;
        }
        double speedKmh = "bicycling".equalsIgnoreCase(travelMode) ? BICYCLING_SPEED_KMH : WALKING_SPEED_KMH;
        return Math.max(1, (int) Math.round((distanceKm / speedKmh) * 3600.0));
    }
}
