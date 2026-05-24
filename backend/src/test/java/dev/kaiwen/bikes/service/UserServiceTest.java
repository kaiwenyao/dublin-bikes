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
import dev.kaiwen.bikes.dto.request.LoginRequestDTO;
import dev.kaiwen.bikes.dto.request.SendVerificationCodeRequestDTO;
import dev.kaiwen.bikes.dto.request.UserRegistrationRequestDTO;
import dev.kaiwen.bikes.dto.response.AuthTokenVO;
import dev.kaiwen.bikes.dto.response.UserVO;
import dev.kaiwen.bikes.exception.AuthException;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.mapper.UserMapper;
import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
