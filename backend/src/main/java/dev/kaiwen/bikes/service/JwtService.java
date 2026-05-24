package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.config.JwtProperties;
import dev.kaiwen.bikes.dto.response.AuthTokenVO;
import dev.kaiwen.bikes.exception.AuthException;
import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.security.JwtTokenClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;

    public AuthTokenVO createTokenPair(User user) {
        String access = createToken(user.getId(), user.getTokenVersion(), false);
        String refresh = createToken(user.getId(), user.getTokenVersion(), true);
        return new AuthTokenVO(access, refresh, jwtProperties.accessExpiresSeconds());
    }

    public JwtTokenClaims parseRefreshToken(String token) {
        return parseToken(token, true);
    }

    public JwtTokenClaims parseAccessToken(String token) {
        return parseToken(token, false);
    }

    private String createToken(int userId, int tokenVersion, boolean refresh) {
        Instant now = Instant.now();
        long expiresSeconds =
                refresh ? jwtProperties.refreshExpiresSeconds() : jwtProperties.accessExpiresSeconds();
        String type = refresh ? "refresh" : "access";
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("ver", tokenVersion)
                .claim("type", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expiresSeconds)))
                .signWith(signingKey(refresh))
                .compact();
    }

    private JwtTokenClaims parseToken(String token, boolean refresh) {
        String expectedType = refresh ? "refresh" : "access";
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(signingKey(refresh))
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();
            String type = claims.get("type", String.class);
            if (!expectedType.equals(type)) {
                throw new AuthException("invalid token");
            }
            Integer ver = claims.get("ver", Integer.class);
            return new JwtTokenClaims(Integer.parseInt(claims.getSubject()), ver, type);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new AuthException("invalid token");
        }
    }

    private SecretKey signingKey(boolean refresh) {
        String secret =
                refresh ? jwtProperties.refreshSecret() : jwtProperties.accessSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret is not configured");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
