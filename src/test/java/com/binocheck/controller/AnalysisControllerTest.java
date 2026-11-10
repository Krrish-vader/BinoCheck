package com.binocheck.controller;

import com.binocheck.config.AuthInterceptor;
import com.binocheck.config.WebMvcConfig;
import com.binocheck.entity.AnalyzedRepo;
import com.binocheck.entity.User;
import com.binocheck.exception.GeminiApiException;
import com.binocheck.repository.AnalyzedRepoRepository;
import com.binocheck.repository.ChatMessageRepository;
import com.binocheck.repository.UserRepository;
import com.binocheck.security.RateLimiter;
import com.binocheck.service.GeminiService;
import com.binocheck.service.GitHubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AnalysisController.class)
@Import({WebMvcConfig.class, AuthInterceptor.class, RateLimiter.class})
class AnalysisControllerTest {

    @Autowired private MockMvc mockMvc;
    @SpyBean private GitHubService gitHubService;
    @MockBean private GeminiService geminiService;
    @MockBean private AnalyzedRepoRepository repoRepository;
    @MockBean private ChatMessageRepository chatRepository;
    @MockBean private UserRepository userRepository;
    @MockBean(name = "githubWebClient") private org.springframework.web.reactive.function.client.WebClient githubWebClient;

    private final User alice = User.builder().id(1L).username("alice").build();
    private final User bob = User.builder().id(2L).username("bob").build();
    private MockHttpSession aliceSession;

    @BeforeEach
    void setUp() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        aliceSession = new MockHttpSession();
        aliceSession.setAttribute("userId", 1L);
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/repos")).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherUsersAnalysisLooksLikeItDoesNotExist() throws Exception {
        AnalyzedRepo bobsRepo = AnalyzedRepo.builder().id(7L).user(bob).repoUrl("https://github.com/b/r")
                .owner("b").repoName("r").analyzedAt(LocalDateTime.now()).build();
        when(repoRepository.findById(7L)).thenReturn(Optional.of(bobsRepo));

        mockMvc.perform(get("/api/repos/7/chat").session(aliceSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/chat").session(aliceSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoId\":7,\"message\":\"hi\"}"))
                .andExpect(status().isNotFound());
        verify(geminiService, never()).generateChatResponse(anyString(), any(), anyString());
    }

    @Test
    void missingRepositoryIsA404NotA500() throws Exception {
        when(repoRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/repos/999/chat").session(aliceSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Repository analysis not found."));
    }

    @Test
    void invalidRequestBodiesAre400s() throws Exception {
        mockMvc.perform(post("/api/analyze").session(aliceSession).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/chat").session(aliceSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/analyze").session(aliceSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/<img src=x onerror=alert(1)>/x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cachedLookupUsesTheCanonicalUrl() throws Exception {
        AnalyzedRepo cached = AnalyzedRepo.builder().id(3L).user(alice).repoUrl("https://github.com/krrish-vader/binocheck")
                .owner("Krrish-vader").repoName("BinoCheck").analyzedAt(LocalDateTime.now()).build();
        when(repoRepository.findByRepoUrlAndUser(eq("https://github.com/krrish-vader/binocheck"), any()))
                .thenReturn(Optional.of(cached));

        mockMvc.perform(post("/api/analyze").session(aliceSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"github.com/Krrish-vader/BinoCheck.git/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3));
        verify(geminiService, never()).analyzeRepository(any());
    }

    private AnalyzedRepo alicesRepo(long id) {
        AnalyzedRepo repo = AnalyzedRepo.builder().id(id).user(alice).repoUrl("https://github.com/a/r")
                .owner("a").repoName("r").analyzedAt(LocalDateTime.now()).build();
        when(repoRepository.findById(id)).thenReturn(Optional.of(repo));
        return repo;
    }

    @Test
    void chatSendsOnlyEarlierMessagesAsHistoryAndStoresBothMessagesAfterTheReply() throws Exception {
        AnalyzedRepo repo = alicesRepo(11L);
        when(geminiService.generateChatResponse(anyString(), any(), eq("What is this?"))).thenReturn("An app.");

        mockMvc.perform(post("/api/chat").session(aliceSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoId\":11,\"message\":\"What is this?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("An app."));

        // The question must not be in the database yet when the history is read,
        // otherwise Gemini receives it twice.
        var order = org.mockito.Mockito.inOrder(chatRepository, geminiService);
        order.verify(chatRepository).findByRepoOrderBySentAtAsc(repo);
        order.verify(geminiService).generateChatResponse(anyString(), any(), eq("What is this?"));
        order.verify(chatRepository).saveAll(org.mockito.ArgumentMatchers.argThat(messages -> {
            java.util.List<String> roles = new java.util.ArrayList<>();
            messages.forEach(m -> roles.add(m.getRole()));
            return roles.equals(java.util.List.of("USER", "MODEL"));
        }));
        verify(chatRepository, never()).save(any());
    }

    @Test
    void failedChatReplyStoresNothing() throws Exception {
        alicesRepo(12L);
        when(geminiService.generateChatResponse(anyString(), any(), anyString()))
                .thenThrow(new GeminiApiException("boom"));

        mockMvc.perform(post("/api/chat").session(aliceSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoId\":12,\"message\":\"hi\"}"))
                .andExpect(status().isBadGateway());

        verify(chatRepository, never()).save(any());
        verify(chatRepository, never()).saveAll(any());
    }

    @Test
    void upstreamAiErrorsAreNotLeakedToTheClient() throws Exception {
        when(repoRepository.findByRepoUrlAndUser(anyString(), any())).thenReturn(Optional.empty());
        doReturn(new java.util.HashMap<String, Object>()).when(gitHubService).collectRepositoryData(anyString());
        when(geminiService.analyzeRepository(any()))
                .thenThrow(new GeminiApiException("Gemini API returned error status 400: API key not valid secret-details"));

        mockMvc.perform(post("/api/analyze").session(aliceSession).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/a/b\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message", not(containsString("secret-details"))));
    }
}
