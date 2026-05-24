package dev.kaiwen.bikes.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kaiwen.bikes.dto.ApiCodes;
import dev.kaiwen.bikes.dto.response.AuthTokenVO;
import dev.kaiwen.bikes.dto.response.UserVO;
import dev.kaiwen.bikes.exception.GlobalExceptionHandler;
import dev.kaiwen.bikes.service.UserService;
import dev.kaiwen.bikes.support.TestJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock private UserService userService;
    @InjectMocks private UserController userController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(userController)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setMessageConverters(TestJson.snakeCaseConverter())
                        .build();
    }

    @Test
    void login_returnsSnakeCaseTokens() throws Exception {
        when(userService.login(any())).thenReturn(new AuthTokenVO("access", "refresh", 900));
        mockMvc.perform(
                        post("/api/users/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"alice","password":"password12"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCodes.SUCCESS))
                .andExpect(jsonPath("$.data.access_token").value("access"))
                .andExpect(jsonPath("$.data.refresh_token").value("refresh"))
                .andExpect(jsonPath("$.data.expires_in").value(900))
                .andExpect(jsonPath("$.data.token_type").value("Bearer"));
    }

}
