package dev.kaiwen.bikes.controller;

import dev.kaiwen.bikes.dto.ApiResponse;
import dev.kaiwen.bikes.dto.request.ActivateByTokenRequestDTO;
import dev.kaiwen.bikes.dto.request.ActivateRequestDTO;
import dev.kaiwen.bikes.dto.request.LoginRequestDTO;
import dev.kaiwen.bikes.dto.request.RefreshTokenRequestDTO;
import dev.kaiwen.bikes.dto.request.SendVerificationCodeRequestDTO;
import dev.kaiwen.bikes.dto.request.UserRegistrationRequestDTO;
import dev.kaiwen.bikes.dto.request.UserRequestNormalizer;
import dev.kaiwen.bikes.dto.response.AuthTokenVO;
import dev.kaiwen.bikes.dto.response.SendVerificationCodeMessageVO;
import dev.kaiwen.bikes.dto.response.UserVO;
import dev.kaiwen.bikes.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ApiResponse<UserVO> register(@Valid @RequestBody UserRegistrationRequestDTO request) {
        return ApiResponse.ok(userService.register(UserRequestNormalizer.normalize(request)));
    }

    @PostMapping("/send-verification-code")
    public ApiResponse<SendVerificationCodeMessageVO> sendVerificationCode(
            @Valid @RequestBody SendVerificationCodeRequestDTO request) {
        return ApiResponse.ok(
                userService.sendVerificationCode(UserRequestNormalizer.normalize(request)));
    }

    @PostMapping("/activate")
    public ApiResponse<UserVO> activate(@Valid @RequestBody ActivateRequestDTO request) {
        return ApiResponse.ok(userService.activate(UserRequestNormalizer.normalize(request)));
    }

    @PostMapping("/activate-by-token")
    public ApiResponse<UserVO> activateByToken(@Valid @RequestBody ActivateByTokenRequestDTO request) {
        return ApiResponse.ok(
                userService.activateByToken(UserRequestNormalizer.normalize(request)));
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokenVO> login(@Valid @RequestBody LoginRequestDTO request) {
        return ApiResponse.ok(userService.login(UserRequestNormalizer.normalize(request)));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokenVO> refresh(@Valid @RequestBody RefreshTokenRequestDTO request) {
        return ApiResponse.ok(userService.refresh(UserRequestNormalizer.normalize(request)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        userService.logout();
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.ok(userService.getCurrentUser());
    }
}
