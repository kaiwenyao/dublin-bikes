package dev.kaiwen.bikes.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WeatherQueryDTOTest {

    @Test
    void recordAccessors_returnConstructorValues() {
        WeatherQueryDTO dto = new WeatherQueryDTO(53.34, -6.26);

        assertThat(dto.lat()).isEqualTo(53.34);
        assertThat(dto.lon()).isEqualTo(-6.26);
    }
}
