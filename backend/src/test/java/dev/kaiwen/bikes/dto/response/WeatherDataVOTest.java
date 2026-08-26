package dev.kaiwen.bikes.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WeatherDataVOTest {

    @Test
    void anySetter_storesAdditionalProperties() {
        WeatherDataVO vo = new WeatherDataVO();

        vo.setAdditional("timezone_offset", 3600);

        assertThat(vo.getAdditional()).containsEntry("timezone_offset", 3600);
    }

    @Test
    void currentAndHourly_roundTrip() {
        WeatherDataVO vo = new WeatherDataVO();
        WeatherCurrentVO current = new WeatherCurrentVO(
                1L, null, null, null, null, null, null, null, null, null, null);
        vo.setCurrent(current);
        vo.setHourly(java.util.List.of(new WeatherHourlyVO(2L, 15.5f)));

        assertThat(vo.getCurrent()).isSameAs(current);
        assertThat(vo.getHourly()).hasSize(1);
    }
}
