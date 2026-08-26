package dev.kaiwen.bikes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.kaiwen.bikes.config.VerificationProperties;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.request.ActivateByTokenRequestDTO;
import dev.kaiwen.bikes.dto.request.ActivateRequestDTO;
import dev.kaiwen.bikes.dto.request.LoginRequestDTO;
import dev.kaiwen.bikes.dto.request.RefreshTokenRequestDTO;
import dev.kaiwen.bikes.dto.request.SendVerificationCodeRequestDTO;
import dev.kaiwen.bikes.dto.request.UserRegistrationRequestDTO;
import dev.kaiwen.bikes.dto.response.AuthTokenVO;
import dev.kaiwen.bikes.dto.response.UserVO;
import dev.kaiwen.bikes.exception.AuthException;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.mapper.UserMapper;
import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.repository.UserRepository;
import dev.kaiwen.bikes.security.AuthenticatedUser;
import dev.kaiwen.bikes.security.JwtTokenClaims;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserMapper userMapper;
    @Mock private JwtService jwtService;
    @Mock private MailService mailService;

    @InjectMocks private UserService userService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);
    private final VerificationProperties verificationProperties = new VerificationProperties(300, 60);

    @BeforeEach
    void injectDependencies() throws Exception {
        setField("passwordEncoder", passwordEncoder);
        setField("verificationProperties", verificationProperties);
    }

    @Test
    void register_setsInactiveAndHashesPassword() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        User saved = new User();
        saved.setId(1);
        saved.setUsername("alice");
        saved.setEmail("alice@example.com");
        saved.setIsActive(false);
        saved.setTokenVersion(0);
        saved.setCreatedAt(LocalDateTime.parse("2025-01-20T10:00:00"));
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(saved);
        when(userRepository.findById(1)).thenReturn(Optional.of(saved));
        UserVO vo = new UserVO(1, "alice", "alice@example.com", null, false, "2025-01-20T10:00:00");
        when(userMapper.toVO(saved)).thenReturn(vo);

        UserVO result =
                userService.register(
                        new UserRegistrationRequestDTO("alice", "alice@example.com", "password12", null));

        assertThat(result.isActive()).isFalse();
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
        assertThat(passwordEncoder.matches("password12", captor.getValue().getPasswordHash()))
                .isTrue();
    }

    @Test
    void sendVerificationCode_unknownIdentifierReturnsGenericSuccess() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThat(
                        userService.sendVerificationCode(
                                new SendVerificationCodeRequestDTO("missing@example.com"))
                        .message())
                .isEqualTo("verification code sent");
        verify(userRepository, never()).save(any(User.class));
        verify(mailService, never()).sendVerificationEmail(any(), any(), any(int.class), any());
    }

    @Test
    void sendVerificationCode_throttleSilentlyNoOpsWithinCooldown() {
        User user = inactiveUser();
        user.setEmailVerificationCodeSentAt(LocalDateTime.now(ZoneOffset.UTC));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThat(
                        userService.sendVerificationCode(
                                new SendVerificationCodeRequestDTO("alice@example.com"))
                        .message())
                .isEqualTo("verification code sent");
        verify(userRepository, never()).save(any(User.class));
        verify(mailService, never()).sendVerificationEmail(any(), any(), any(int.class), any());
    }

    @Test
    void activateByToken_rejectsExpiredToken() {
        User user = inactiveUser();
        user.setActivationToken("expired-token");
        user.setEmailVerificationCodeExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(userRepository.findByActivationToken("expired-token")).thenReturn(Optional.of(user));

        assertThatThrownBy(
                        () ->
                                userService.activateByToken(
                                        new ActivateByTokenRequestDTO("expired-token")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid credentials");
        assertThat(user.getIsActive()).isFalse();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void login_rejectsInactiveUserWithEmailNotVerified() {
        User user = inactiveUser();
        user.setPasswordHash(passwordEncoder.encode("password12"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(
                        () -> userService.login(new LoginRequestDTO("alice", "password12")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException business = (BusinessException) ex;
                            assertThat(business.getCode()).isEqualTo(ApiCodes.EMAIL_NOT_VERIFIED);
                            assertThat(business.getStatus()).isEqualTo(403);
                            assertThat(business.getMessage()).isEqualTo("email not verified");
                        });
    }

    @Test
    void login_returnsTokensForActiveUser() {
        User user = activeUser();
        user.setPasswordHash(passwordEncoder.encode("password12"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        AuthTokenVO tokens = new AuthTokenVO("a", "r", 900);
        when(jwtService.createTokenPair(user)).thenReturn(tokens);

        assertThat(userService.login(new LoginRequestDTO("alice", "password12"))).isEqualTo(tokens);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void register_duplicateEmail_throws409() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                userService.register(
                                        new UserRegistrationRequestDTO(
                                                "alice", "alice@example.com", "password12", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getCode()).isEqualTo(ApiCodes.EMAIL_EXISTS);
                            assertThat(be.getStatus()).isEqualTo(409);
                        });
    }

    @Test
    void sendVerificationCode_existingUser_persistsCodeAndSendsMail() {
        User user = inactiveUser();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        userService.sendVerificationCode(new SendVerificationCodeRequestDTO("alice@example.com"));

        assertThat(user.getEmailVerificationCode()).hasSize(6);
        assertThat(user.getEmailVerificationCodeExpiresAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(user.getEmailVerificationCodeSentAt()).isNotNull();
        assertThat(user.getActivationToken()).isNotBlank().hasSizeLessThanOrEqualTo(64);
        verify(userRepository).save(user);
        verify(mailService)
                .sendVerificationEmail(
                        eq("alice@example.com"), eq(user.getEmailVerificationCode()), eq(5), eq(user.getActivationToken()));
    }

    @Test
    void sendVerificationCode_cooldownExpired_resends() {
        User user = inactiveUser();
        user.setEmailVerificationCodeSentAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(120));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        userService.sendVerificationCode(new SendVerificationCodeRequestDTO("alice@example.com"));

        verify(userRepository).save(user);
        verify(mailService).sendVerificationEmail(any(), any(), any(int.class), any());
    }

    @Test
    void activate_happyPath_activatesAndClearsVerificationFields() {
        User user = inactiveUser();
        user.setEmailVerificationCode("654321");
        user.setEmailVerificationCodeExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        user.setEmailVerificationCodeSentAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setActivationToken("token");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        UserVO vo = new UserVO(1, "alice", "alice@example.com", null, true, "2025-01-20T10:00:00");
        when(userMapper.toVO(user)).thenReturn(vo);

        UserVO result =
                userService.activate(new ActivateRequestDTO("alice@example.com", "654321"));

        assertThat(result.isActive()).isTrue();
        assertThat(user.getIsActive()).isTrue();
        assertThat(user.getEmailVerificationCode()).isNull();
        assertThat(user.getEmailVerificationCodeExpiresAt()).isNull();
        assertThat(user.getEmailVerificationCodeSentAt()).isNull();
        assertThat(user.getActivationToken()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void activate_unknownIdentifier_throwsAuth() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.activate(new ActivateRequestDTO("ghost", "123456")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid credentials");
    }

    @Test
    void activate_wrongCode_throwsAuth() {
        User user = inactiveUser();
        user.setEmailVerificationCode("654321");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.activate(new ActivateRequestDTO("alice", "000000")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid credentials");
        assertThat(user.getIsActive()).isFalse();
    }

    @Test
    void activate_expiredCode_throwsAuth() {
        User user = inactiveUser();
        user.setEmailVerificationCode("654321");
        user.setEmailVerificationCodeExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.activate(new ActivateRequestDTO("alice", "654321")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid credentials");
        assertThat(user.getIsActive()).isFalse();
    }

    @Test
    void activate_noCodeIssued_throwsAuth() {
        User user = inactiveUser();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.activate(new ActivateRequestDTO("alice", "654321")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid credentials");
        assertThat(user.getIsActive()).isFalse();
    }

    @Test
    void activateByToken_happyPath_activates() {
        User user = inactiveUser();
        user.setActivationToken("valid-token");
        user.setEmailVerificationCodeExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        when(userRepository.findByActivationToken("valid-token")).thenReturn(Optional.of(user));
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        UserVO vo = new UserVO(1, "alice", "alice@example.com", null, true, "2025-01-20T10:00:00");
        when(userMapper.toVO(user)).thenReturn(vo);

        UserVO result = userService.activateByToken(new ActivateByTokenRequestDTO("valid-token"));

        assertThat(result.isActive()).isTrue();
        assertThat(user.getActivationToken()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void activateByToken_unknownToken_throwsAuth() {
        when(userRepository.findByActivationToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.activateByToken(new ActivateByTokenRequestDTO("nope")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid credentials");
    }

    @Test
    void login_unknownIdentifier_throwsAuth() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(new LoginRequestDTO("ghost", "password12")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid credentials");
    }

    @Test
    void login_wrongPassword_throwsAuth() {
        User user = activeUser();
        user.setPasswordHash(passwordEncoder.encode("password12"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.login(new LoginRequestDTO("alice", "wrong-password")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid credentials");
    }

    @Test
    void refresh_happyPath_returnsNewTokenPair() {
        when(jwtService.parseRefreshToken("refresh-token"))
                .thenReturn(new JwtTokenClaims(1, 0, "refresh"));
        User user = activeUser();
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        AuthTokenVO tokens = new AuthTokenVO("new-a", "new-r", 900);
        when(jwtService.createTokenPair(user)).thenReturn(tokens);

        assertThat(userService.refresh(new RefreshTokenRequestDTO("refresh-token")))
                .isEqualTo(tokens);
    }

    @Test
    void refresh_userMissing_throwsAuth() {
        when(jwtService.parseRefreshToken("refresh-token"))
                .thenReturn(new JwtTokenClaims(99, 0, "refresh"));
        when(userRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.refresh(new RefreshTokenRequestDTO("refresh-token")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid token");
    }

    @Test
    void refresh_tokenVersionMismatch_throwsAuth() {
        when(jwtService.parseRefreshToken("refresh-token"))
                .thenReturn(new JwtTokenClaims(1, 0, "refresh"));
        User user = activeUser();
        user.setTokenVersion(5);
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.refresh(new RefreshTokenRequestDTO("refresh-token")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid token");
    }

    @Test
    void refresh_inactiveUser_throwsAuth() {
        when(jwtService.parseRefreshToken("refresh-token"))
                .thenReturn(new JwtTokenClaims(1, 0, "refresh"));
        User user = inactiveUser();
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.refresh(new RefreshTokenRequestDTO("refresh-token")))
                .isInstanceOf(AuthException.class)
                .hasMessage("invalid token");
    }

    @Test
    void logout_incrementsTokenVersion() {
        authenticateAs(1);
        User user = activeUser();
        when(userRepository.findById(1)).thenReturn(Optional.of(user));

        userService.logout();

        assertThat(user.getTokenVersion()).isEqualTo(1);
        verify(userRepository).save(user);
    }

    @Test
    void getCurrentUser_returnsMappedVO() {
        authenticateAs(1);
        User user = activeUser();
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
        UserVO vo = new UserVO(1, "alice", "alice@example.com", null, true, "2025-01-20T10:00:00");
        when(userMapper.toVO(user)).thenReturn(vo);

        assertThat(userService.getCurrentUser()).isEqualTo(vo);
    }

    @Test
    void getCurrentUser_unauthenticated_throwsAuth() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> userService.getCurrentUser())
                .isInstanceOf(AuthException.class)
                .hasMessage("unauthorized");
    }

    @Test
    void getCurrentUser_userMissingFromDb_throwsAuth() {
        authenticateAs(99);
        when(userRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser())
                .isInstanceOf(AuthException.class)
                .hasMessage("unauthorized");
    }

    private static void authenticateAs(int userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new AuthenticatedUser(userId),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static User inactiveUser() {
        User user = new User();
        user.setId(1);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setIsActive(false);
        user.setTokenVersion(0);
        return user;
    }

    private static User activeUser() {
        User user = inactiveUser();
        user.setIsActive(true);
        return user;
    }

    private void setField(String name, Object value) throws Exception {
        var field = UserService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(userService, value);
    }
}
