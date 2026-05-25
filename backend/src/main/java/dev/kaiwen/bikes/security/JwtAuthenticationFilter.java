package dev.kaiwen.bikes.security;

import dev.kaiwen.bikes.exception.AuthException;
import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.repository.UserRepository;
import dev.kaiwen.bikes.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        log.debug(
                "Authorization header present for {} {}: {}",
                request.getMethod(),
                request.getRequestURI(),
                token != null);
        if (token != null) {
            try {
                JwtTokenClaims claims = jwtService.parseAccessToken(token);
                User user = loadActiveUser(claims);
                var authentication =
                        new UsernamePasswordAuthenticationToken(
                                new AuthenticatedUser(user.getId()),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (AuthException ex) {
                log.debug(
                        "Token parsing failed for {} {}: {}",
                        request.getMethod(),
                        request.getRequestURI(),
                        ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private User loadActiveUser(JwtTokenClaims claims) {
        User user =
                userRepository
                        .findById(claims.userId())
                        .orElseThrow(() -> new AuthException("invalid token"));
        if (!user.getTokenVersion().equals(claims.tokenVersion())) {
            throw new AuthException("invalid token");
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new AuthException("invalid token");
        }
        return user;
    }

    private String resolveToken(HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return null;
    }
}
