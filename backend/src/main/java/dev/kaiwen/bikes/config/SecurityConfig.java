package dev.kaiwen.bikes.config;

import dev.kaiwen.bikes.security.JwtAuthenticationEntryPoint;
import dev.kaiwen.bikes.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/actuator/health")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/stations/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/weather")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/journey/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/users/register")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/users/send-verification-code")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/users/activate")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST, "/api/users/activate-by-token")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/users/login")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/users/refresh")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/users/me")
                                        .authenticated()
                                        .requestMatchers(HttpMethod.POST, "/api/users/logout")
                                        .authenticated()
                                        .requestMatchers("/api/chat/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
