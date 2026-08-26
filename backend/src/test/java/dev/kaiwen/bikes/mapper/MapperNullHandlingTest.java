package dev.kaiwen.bikes.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kaiwen.bikes.model.Availability;
import dev.kaiwen.bikes.model.Station;
import dev.kaiwen.bikes.model.WeatherForecast;
import org.junit.jupiter.api.Test;

/**
 * MapStruct 生成实现类的 null 容忍分支 + 接口 default 方法覆盖。
 * 生成代码位于 target/generated-sources，直接实例化 *Impl 验证。
 */
class MapperNullHandlingTest {

    private final StationMapper stationMapper = new StationMapperImpl();
    private final WeatherMapper weatherMapper = new WeatherMapperImpl();
    private final UserMapper userMapper = new UserMapperImpl();

    @Test
    void stationMapper_nullInputs_returnNull() {
        assertThat(stationMapper.toVO((Station) null)).isNull();
        assertThat(stationMapper.toVOList(null)).isNull();
        assertThat(stationMapper.toVO((Availability) null)).isNull();
        assertThat(stationMapper.toAvailabilityVOList(null)).isNull();
        assertThat(stationMapper.map(null)).isNull();
    }

    @Test
    void stationMapper_entityWithNullFields_mapsToNullFields() {
        assertThat(stationMapper.toVO(new Station()).name()).isNull();
        assertThat(stationMapper.toVO(new Availability()).timestamp()).isNull();
    }

    @Test
    void weatherMapper_nullInputs_returnNull() {
        assertThat(weatherMapper.toCurrentVO(null)).isNull();
        assertThat(weatherMapper.toHourlyVO(null)).isNull();
        assertThat(weatherMapper.toHourlyVOList(null)).isNull();
        assertThat(weatherMapper.toEpochSecond(null)).isNull();
    }

    @Test
    void weatherMapper_forecastWithNullFields_mapsNullDt() {
        WeatherForecast bare = new WeatherForecast();

        assertThat(weatherMapper.toCurrentVO(bare).dt()).isNull();
        assertThat(weatherMapper.toHourlyVO(bare).dt()).isNull();
    }

    @Test
    void userMapper_nullInput_returnsNull() {
        assertThat(userMapper.toVO(null)).isNull();
        assertThat(userMapper.map(null)).isNull();
    }
}
