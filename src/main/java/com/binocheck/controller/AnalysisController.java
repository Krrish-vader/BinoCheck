package com.binocheck.controller;

import com.binocheck.dto.*;
import com.binocheck.entity.AnalyzedRepo;
import com.binocheck.entity.ChatMessage;
import com.binocheck.entity.User;
import com.binocheck.exception.ResourceNotFoundException;
import com.binocheck.repository.AnalyzedRepoRepository;
import com.binocheck.repository.ChatMessageRepository;
import com.binocheck.repository.UserRepository;
import com.binocheck.security.RateLimiter;
import com.binocheck.service.GitHubService;
import com.binocheck.service.GeminiService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    // Each analysis and chat message is a paid Gemini call; cap them per user.
    static final int ANALYSES_PER_HOUR = 10;
    static final int CHAT_MESSAGES_PER_HOUR = 60;

    private final GitHubService gitHubService;
    private final GeminiService geminiService;
    private final AnalyzedRepoRepository repoRepository;
    private final ChatMessageRepository chatRepository;
    private final UserRepository userRepository;
    private final RateLimiter rateLimiter;

    @Autowired
    public AnalysisController(GitHubService gitHubService,
                              GeminiService geminiService,
                              AnalyzedRepoRepository repoRepository,
                              ChatMessageRepository chatRepository,
                              UserRepository userRepository,
                              RateLimiter rateLimiter) {
        this.gitHubService = gitHubService;
        this.geminiService = geminiService;
        this.repoRepository = repoRepository;
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Loads a repository analysis owned by {@code user}. Another user's analysis is reported as
     * "not found" (not "forbidden") so IDs belonging to other users can't be probed.
     */
    private AnalyzedRepo findOwnedRepo(Long id, User user) {
        return repoRepository.findById(id)
                .filter(repo -> repo.getUser() != null && repo.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Repository analysis not found."));
    }

    private User getAuthenticatedUser(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            throw new IllegalStateException("Unauthorized user context");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyzeRepository(@Valid @RequestBody AnalyzeRequest request, HttpSession session) {
        User user = getAuthenticatedUser(session);
        String inputUrl = request.getRepoUrl().trim();
        // Validates the URL and normalises it, so "github.com/A/B", ".../a/b/" and ".../a/b.git" share one cache entry
        // and only a plain https://github.com/... link is ever stored (and later rendered as a link).
        String cleanedUrl = gitHubService.canonicalUrl(gitHubService.parseUrl(inputUrl));
        var existing = repoRepository.findByRepoUrlAndUser(cleanedUrl, user);
        if (existing.isPresent()) {
            return ResponseEntity.ok(mapToResponse(existing.get()));
        }

        rateLimiter.check("analyze:" + user.getId(), ANALYSES_PER_HOUR, Duration.ofHours(1));
        Map<String, Object> repoData = gitHubService.collectRepositoryData(inputUrl);
        String analysisJson = geminiService.analyzeRepository(repoData);

        GitHubRepoResponse metadata = (GitHubRepoResponse) repoData.get("metadata");
        AnalyzedRepo repo = AnalyzedRepo.builder()
                .user(user)
                .repoUrl(cleanedUrl)
                .owner((String) repoData.get("owner"))
                .repoName((String) repoData.get("repoName"))
                .description(metadata.getDescription())
                .primaryLanguage(metadata.getLanguage())
                .starsCount(metadata.getStargazersCount())
                .analysisResult(analysisJson)
                .fileTreeJson((String) repoData.get("fileTreeJson"))
                .analyzedAt(LocalDateTime.now())
                .build();

        AnalyzedRepo saved = repoRepository.save(repo);
        return ResponseEntity.ok(mapToResponse(saved));
    }

    @GetMapping("/repos")
    public ResponseEntity<List<AnalyzeResponse>> getAnalyzedRepositories(HttpSession session) {
        User user = getAuthenticatedUser(session);
        List<AnalyzedRepo> repos = repoRepository.findByUserOrderByAnalyzedAtDesc(user);
        List<AnalyzeResponse> responses = repos.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chatAboutRepo(@Valid @RequestBody ChatRequest request, HttpSession session) {
        User user = getAuthenticatedUser(session);
        AnalyzedRepo repo = findOwnedRepo(request.getRepoId(), user);
        rateLimiter.check("chat:" + user.getId(), CHAT_MESSAGES_PER_HOUR, Duration.ofHours(1));

        // History is loaded before the new question exists in the database, so it holds only earlier
        // messages; GeminiService appends the current question itself.
        List<ChatMessage> history = chatRepository.findByRepoOrderBySentAtAsc(repo);

        ChatMessage userMsg = ChatMessage.builder()
                .repo(repo)
                .role("USER")
                .messageText(request.getMessage())
                .sentAt(LocalDateTime.now())
                .build();

        String repoContext = String.format(
                "Repository: %s/%s\nDescription: %s\nPrimary Language: %s\nStars: %d\n\nInitial Analysis (JSON):\n%s\n\nFile Tree:\n%s",
                repo.getOwner(), repo.getRepoName(),
                repo.getDescription() != null ? repo.getDescription() : "N/A",
                repo.getPrimaryLanguage() != null ? repo.getPrimaryLanguage() : "N/A",
                repo.getStarsCount() != null ? repo.getStarsCount() : 0,
                repo.getAnalysisResult(),
                repo.getFileTreeJson()
        );

        String reply = geminiService.generateChatResponse(repoContext, history, request.getMessage());

        ChatMessage modelMsg = ChatMessage.builder()
                .repo(repo)
                .role("MODEL")
                .messageText(reply)
                .sentAt(LocalDateTime.now())
                .build();
        // Question and answer are stored together, only after Gemini replied. A failed call leaves no
        // unanswered question behind in the chat history.
        chatRepository.saveAll(List.of(userMsg, modelMsg));

        return ResponseEntity.ok(ChatResponse.builder()
                .role("MODEL")
                .message(reply)
                .sentAt(modelMsg.getSentAt())
                .build());
    }

    @GetMapping("/repos/{id}/chat")
    public ResponseEntity<?> getChatHistory(@PathVariable Long id, HttpSession session) {
        User user = getAuthenticatedUser(session);
        AnalyzedRepo repo = findOwnedRepo(id, user);

        List<ChatMessage> history = chatRepository.findByRepoOrderBySentAtAsc(repo);
        List<ChatResponse> responses = history.stream()
                .map(msg -> ChatResponse.builder()
                        .role(msg.getRole())
                        .message(msg.getMessageText())
                        .sentAt(msg.getSentAt())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    private AnalyzeResponse mapToResponse(AnalyzedRepo repo) {
        return AnalyzeResponse.builder()
                .id(repo.getId())
                .repoUrl(repo.getRepoUrl())
                .owner(repo.getOwner())
                .repoName(repo.getRepoName())
                .description(repo.getDescription())
                .primaryLanguage(repo.getPrimaryLanguage())
                .starsCount(repo.getStarsCount())
                .analysisResult(repo.getAnalysisResult())
                .fileTreeJson(repo.getFileTreeJson())
                .analyzedAt(repo.getAnalyzedAt())
                .build();
    }
}
