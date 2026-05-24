package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.config.VerificationProperties;
import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.request.ActivateByTokenRequestDTO;
import dev.kaiwen.bikes.dto.request.ActivateRequestDTO;
import dev.kaiwen.bikes.dto.request.LoginRequestDTO;
import dev.kaiwen.bikes.dto.request.RefreshTokenRequestDTO;
import dev.kaiwen.bikes.dto.request.SendVerificationCodeRequestDTO;
import dev.kaiwen.bikes.dto.request.UserRegistrationRequestDTO;
import dev.kaiwen.bikes.dto.response.AuthTokenVO;
import dev.kaiwen.bikes.dto.response.SendVerificationCodeMessageVO;
import dev.kaiwen.bikes.dto.response.UserVO;
import dev.kaiwen.bikes.exception.AuthException;
import dev.kaiwen.bikes.exception.BusinessException;
import dev.kaiwen.bikes.mapper.UserMapper;
import dev.kaiwen.bikes.model.User;
import dev.kaiwen.bikes.repository.UserRepository;
import dev.kaiwen.bikes.security.AuthenticatedUser;
import dev.kaiwen.bikes.security.JwtTokenClaims;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String AUTH_FAILED = "invalid credentials";

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final VerificationProperties verificationProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public UserVO register(UserRegistrationRequestDTO request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessException(ApiCodes.USERNAME_EXISTS, "username already exists", 409);
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ApiCodes.EMAIL_EXISTS, "email already exists", 409);
        }
        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setAvatarUrl(request.avatarUrl());
        user.setIsActive(false);
        user.setTokenVersion(0);
        User saved = userRepository.saveAndFlush(user);
        return userMapper.toVO(reload(saved.getId()));
    }

    @Transactional
    public SendVerificationCodeMessageVO sendVerificationCode(SendVerificationCodeRequestDTO request) {
        Optional<User> userOpt = findByIdentifier(request.identifier());
        if (userOpt.isEmpty()) {
            return SendVerificationCodeMessageVO.defaults();
        }
        User user = userOpt.get();
        LocalDateTime now = utcNow();
        if (user.getEmailVerificationCodeSentAt() != null) {
            LocalDateTime allowed =
                    user.getEmailVerificationCodeSentAt()
                            .plusSeconds(verificationProperties.resendCooldownSeconds());
            if (now.isBefore(allowed)) {
                return SendVerificationCodeMessageVO.defaults();
            }
        }
        String code = generateVerificationCode();
        String activationToken = generateActivationToken();
        user.setEmailVerificationCode(code);
        user.setEmailVerificationCodeExpiresAt(
                now.plusSeconds(verificationProperties.codeExpireSeconds()));
        user.setEmailVerificationCodeSentAt(now);
        user.setActivationToken(activationToken);
        userRepository.save(user);
        int expiresMinutes = verificationProperties.codeExpireSeconds() / 60;
        mailService.sendVerificationEmail(user.getEmail(), code, expiresMinutes, activationToken);
        return SendVerificationCodeMessageVO.defaults();
    }

    @Transactional
    public UserVO activate(ActivateRequestDTO request) {
        User user =
                findByIdentifier(request.identifier())
                        .orElseThrow(() -> new AuthException(AUTH_FAILED));
        validateVerificationCode(user, request.code());
        activateUser(user);
        return userMapper.toVO(reload(user.getId()));
    }

    @Transactional
    public UserVO activateByToken(ActivateByTokenRequestDTO request) {
        User user =
                userRepository
                        .findByActivationToken(request.token())
                        .orElseThrow(() -> new AuthException(AUTH_FAILED));
        activateUser(user);
        return userMapper.toVO(reload(user.getId()));
    }

    @Transactional(readOnly = true)
    public AuthTokenVO login(LoginRequestDTO request) {
        User user =
                findByIdentifier(request.identifier())
                        .orElseThrow(() -> new AuthException(AUTH_FAILED));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new AuthException(AUTH_FAILED);
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new AuthException(AUTH_FAILED);
        }
        return jwtService.createTokenPair(user);
    }

    @Transactional(readOnly = true)
    public AuthTokenVO refresh(RefreshTokenRequestDTO request) {
        JwtTokenClaims claims = jwtService.parseRefreshToken(request.refreshToken());
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
        return jwtService.createTokenPair(user);
    }

    @Transactional
    public void logout() {
        User user = loadCurrentUser();
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserVO getCurrentUser() {
        return userMapper.toVO(loadCurrentUser());
    }

    private User loadCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new AuthException("unauthorized");
        }
        return userRepository
                .findById(principal.userId())
                .orElseThrow(() -> new AuthException("unauthorized"));
    }

    private void activateUser(User user) {
        user.setIsActive(true);
        user.setEmailVerificationCode(null);
        user.setEmailVerificationCodeExpiresAt(null);
        user.setEmailVerificationCodeSentAt(null);
        user.setActivationToken(null);
        userRepository.save(user);
    }

    private void validateVerificationCode(User user, String code) {
        if (!verificationCodesMatch(user.getEmailVerificationCode(), code)) {
            throw new AuthException(AUTH_FAILED);
        }
        if (user.getEmailVerificationCodeExpiresAt() == null
                || utcNow().isAfter(user.getEmailVerificationCodeExpiresAt())) {
            throw new AuthException(AUTH_FAILED);
        }
    }

    private Optional<User> findByIdentifier(String identifier) {
        if (identifier != null && identifier.contains("@") && EMAIL_PATTERN.matcher(identifier).matches()) {
            return userRepository.findByEmail(identifier.toLowerCase());
        }
        return userRepository.findByUsername(identifier);
    }

    private User reload(int userId) {
        return userRepository.findById(userId).orElseThrow();
    }

    private String generateVerificationCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private String generateActivationToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (token.length() > 64) {
            return token.substring(0, 64);
        }
        return token;
    }

    private static boolean verificationCodesMatch(String stored, String provided) {
        if (stored == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
