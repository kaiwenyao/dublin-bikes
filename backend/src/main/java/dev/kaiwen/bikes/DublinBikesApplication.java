package dev.kaiwen.bikes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan("dev.kaiwen.bikes.config")
@EntityScan(basePackages = "dev.kaiwen.bikes.model")
@EnableJpaRepositories(basePackages = "dev.kaiwen.bikes.repository")
public class DublinBikesApplication {

	public static void main(String[] args) {
		SpringApplication.run(DublinBikesApplication.class, args);
	}
}
