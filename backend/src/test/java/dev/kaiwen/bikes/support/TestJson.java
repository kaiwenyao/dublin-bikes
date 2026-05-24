package dev.kaiwen.bikes.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

public final class TestJson {

    private TestJson() {
    }

    public static MappingJackson2HttpMessageConverter snakeCaseConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        return new MappingJackson2HttpMessageConverter(mapper);
    }
}
