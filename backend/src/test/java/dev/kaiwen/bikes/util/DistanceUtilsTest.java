package dev.kaiwen.bikes.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class DistanceUtilsTest {

    @Test
    void haversineKm_samePoint_returnsZero() {
        assertThat(DistanceUtils.haversineKm(53.34, -6.26, 53.34, -6.26)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void haversineKm_dublinPoints_returnsExpectedDistance() {
        double km = DistanceUtils.haversineKm(53.3498, -6.2603, 53.3439, -6.2546);
        assertThat(km).isBetween(0.5, 1.5);
    }

    @Test
    void haversineKm_clampsIntermediateValue() {
        double km = DistanceUtils.haversineKm(0.0, 0.0, 0.0, 180.0);
        assertThat(km).isBetween(19900.0, 20100.0);
    }

    @Test
    void estimatedDurationSeconds_walking_usesFiveKmh() {
        assertThat(DistanceUtils.estimatedDurationSeconds(1.0, "walking")).isEqualTo(720);
    }

    @Test
    void estimatedDurationSeconds_bicycling_usesFourteenKmh() {
        assertThat(DistanceUtils.estimatedDurationSeconds(1.0, "bicycling")).isEqualTo(257);
    }

    @Test
    void estimatedDurationSeconds_zeroDistance_returnsZero() {
        assertThat(DistanceUtils.estimatedDurationSeconds(0.0, "walking")).isZero();
    }
}
