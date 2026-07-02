package dev.kaiwen.bikes.config;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import dev.kaiwen.bikes.security.JwtAuthenticationEntryPoint;
import dev.kaiwen.bikes.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
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
                // antMatcher() forces path-only matching. Spring Security 6's default
                // MvcRequestMatcher additionally negotiates on Accept / Content-Type, which
                // makes any request whose Accept does not match a handler's `produces` skip
                // every rule and fall through to denyAll() — e.g. fetchEventSource sends
                // `Accept: text/event-stream` and got 401 even with a valid JWT.
                .authorizeHttpRequests(
                        auth ->
                                // SSE responses finish through async/error redispatches after bytes may be committed.
                                auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(antMatcher("/error"))
                                        .permitAll()
                                        .requestMatchers(antMatcher("/actuator/health"))
                                        .permitAll()
                                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/stations/**"))
                                        .permitAll()
                                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/weather"))
                                        .permitAll()
                                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/journey/**"))
                                        .permitAll()
                                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/users/register"))
                                        .permitAll()
                                        .requestMatchers(
                                                antMatcher(HttpMethod.POST, "/api/users/send-verification-code"))
                                        .permitAll()
                                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/users/activate"))
                                        .permitAll()
                                        .requestMatchers(
                                                antMatcher(HttpMethod.POST, "/api/users/activate-by-token"))
                                        .permitAll()
                                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/users/login"))
                                        .permitAll()
                                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/users/refresh"))
                                        .permitAll()
                                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/users/me"))
                                        .authenticated()
                                        .requestMatchers(antMatcher(HttpMethod.POST, "/api/users/logout"))
                                        .authenticated()
                                        .requestMatchers(antMatcher("/api/chat/**"))
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
