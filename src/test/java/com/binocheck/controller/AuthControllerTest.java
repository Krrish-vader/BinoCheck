package com.binocheck.controller;

import com.binocheck.config.AuthInterceptor;
import com.binocheck.config.WebMvcConfig;
import com.binocheck.entity.User;
import com.binocheck.repository.UserRepository;
import com.binocheck.security.RateLimiter;
import com.binocheck.security.SecurityHeadersFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({WebMvcConfig.class, AuthInterceptor.class, RateLimiter.class, SecurityHeadersFilter.class})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private UserRepository userRepository;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = User.builder().id(1L).username("alice")
                .passwordHash(passwordEncoder.encode("correct-horse"))
                .createdAt(LocalDateTime.now()).build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());
    }

    private static String json(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void loginIssuesANewSessionIdSoAPlantedSessionIsUseless() throws Exception {
        MockHttpSession planted = new MockHttpSession();
        String plantedId = planted.getId();

        MvcResult result = mockMvc.perform(post("/api/auth/login").session(planted)
                        .contentType(MediaType.APPLICATION_JSON).content(json("alice", "correct-horse")))
                .andExpect(status().isOk())
                .andReturn();

        String sessionIdAfterLogin = result.getRequest().getSession().getId();
        assertThat(sessionIdAfterLogin).isNotEqualTo(plantedId);
        assertThat(result.getRequest().getSession().getAttribute("userId")).isEqualTo(1L);
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameResponse() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("alice", "wrong-pass")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password."));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("nobody", "whatever1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password."));
    }

    @Test
    void loginIsRateLimitedPerClient() throws Exception {
        // Its own client IP, so this test doesn't use up the limit shared with the other tests.
        org.springframework.test.web.servlet.request.RequestPostProcessor attacker =
                req -> { req.setRemoteAddr("203.0.113.9"); return req; };
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/login").with(attacker).contentType(MediaType.APPLICATION_JSON).content(json("alice", "wrong-pass")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login").with(attacker).contentType(MediaType.APPLICATION_JSON).content(json("alice", "correct-horse")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // Other clients are unaffected.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("alice", "correct-horse")))
                .andExpect(status().isOk());
    }

    @Test
    void signupRejectsShortPasswords() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json("newuser", "x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password must be between 8 and 128 characters."));
        verify(userRepository, never()).save(any());
    }

    @Test
    void signupRejectsInvalidUsernamesInsteadOfFailingInTheDatabase() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json("a".repeat(300), "long-enough-pw")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json("<script>", "long-enough-pw")))
                .andExpect(status().isBadRequest());
        verify(userRepository, never()).save(any());
    }

    @Test
    void signupAcceptsAValidAccount() throws Exception {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json("New.User", "long-enough-pw")))
                .andExpect(status().isCreated());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void malformedJsonIsA400NotA500() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossOriginRequestsAreNotAllowed() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
