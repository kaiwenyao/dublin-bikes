package dev.kaiwen.bikes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kaiwen.bikes.config.JwtProperties;
import dev.kaiwen.bikes.exception.AuthException;
import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.security.JwtTokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService =
                new JwtService(
                        new JwtProperties(
                                "test-access-secret-key-at-least-32-bytes!!",
                                "test-refresh-secret-key-at-least-32-bytes!!",
                                900,
                                604800));
    }

    @Test
    void createTokenPair_andParseAccessToken() {
        User user = new User();
        user.setId(1);
        user.setTokenVersion(2);
        var tokens = jwtService.createTokenPair(user);
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.expiresIn()).isEqualTo(900);
        assertThat(tokens.tokenType()).isEqualTo("Bearer");

        JwtTokenClaims claims = jwtService.parseAccessToken(tokens.accessToken());
        assertThat(claims.userId()).isEqualTo(1);
        assertThat(claims.tokenVersion()).isEqualTo(2);
        assertThat(claims.type()).isEqualTo("access");
    }


    @Test
    void parseAccessToken_rejectsTokenMissingVerClaim() {
        User user = new User();
        user.setId(1);
        user.setTokenVersion(0);
        String refresh = jwtService.createTokenPair(user).refreshToken();
        assertThatThrownBy(() -> jwtService.parseAccessToken(refresh))
                .isInstanceOf(AuthException.class);

        String tokenWithoutVer =
                io.jsonwebtoken.Jwts.builder()
                        .subject("1")
                        .claim("type", "access")
                        .issuedAt(new java.util.Date())
                        .expiration(
                                java.util.Date.from(
                                        java.time.Instant.now().plusSeconds(900)))
                        .signWith(
                                io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                                        "test-access-secret-key-at-least-32-bytes!!"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .compact();
        assertThatThrownBy(() -> jwtService.parseAccessToken(tokenWithoutVer))
                .isInstanceOf(AuthException.class);
    }
    @Test
    void parseAccessToken_rejectsRefreshToken() {
        User user = new User();
        user.setId(1);
        user.setTokenVersion(0);
        String refresh = jwtService.createTokenPair(user).refreshToken();
        assertThatThrownBy(() -> jwtService.parseAccessToken(refresh))
                .isInstanceOf(AuthException.class);
    }
}
