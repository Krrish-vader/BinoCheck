package com.binocheck.controller;

import com.binocheck.dto.*;
import com.binocheck.entity.AnalyzedRepo;
import com.binocheck.entity.ChatMessage;
import com.binocheck.entity.User;
import com.binocheck.repository.AnalyzedRepoRepository;
import com.binocheck.repository.ChatMessageRepository;
import com.binocheck.repository.UserRepository;
import com.binocheck.service.GitHubService;
import com.binocheck.service.GeminiService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AnalysisController {

    private final GitHubService gitHubService;
    private final GeminiService geminiService;
    private final AnalyzedRepoRepository repoRepository;
    private final ChatMessageRepository chatRepository;
    private final UserRepository userRepository;

    @Autowired
    public AnalysisController(GitHubService gitHubService,
                              GeminiService geminiService,
                              AnalyzedRepoRepository repoRepository,
                              ChatMessageRepository chatRepository,
                              UserRepository userRepository) {
        this.gitHubService = gitHubService;
        this.geminiService = geminiService;
        this.repoRepository = repoRepository;
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
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
    public ResponseEntity<AnalyzeResponse> analyzeRepository(@RequestBody AnalyzeRequest request, HttpSession session) {
        User user = getAuthenticatedUser(session);
        String cleanedUrl = request.getRepoUrl().trim();
        var existing = repoRepository.findByRepoUrlAndUser(cleanedUrl, user);
        if (existing.isPresent()) {
            return ResponseEntity.ok(mapToResponse(existing.get()));
        }

        Map<String, Object> repoData = gitHubService.collectRepositoryData(cleanedUrl);
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
    public ResponseEntity<?> chatAboutRepo(@RequestBody ChatRequest request, HttpSession session) {
        User user = getAuthenticatedUser(session);
        AnalyzedRepo repo = repoRepository.findById(request.getRepoId())
                .orElseThrow(() -> new IllegalArgumentException("Repository not found with ID: " + request.getRepoId()));

        if (!repo.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied to this repository analysis"));
        }

        ChatMessage userMsg = ChatMessage.builder()
                .repo(repo)
                .role("USER")
                .messageText(request.getMessage())
                .sentAt(LocalDateTime.now())
                .build();
        chatRepository.save(userMsg);

        List<ChatMessage> history = chatRepository.findByRepoOrderBySentAtAsc(repo);

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
        chatRepository.save(modelMsg);

        return ResponseEntity.ok(ChatResponse.builder()
                .role("MODEL")
                .message(reply)
                .sentAt(modelMsg.getSentAt())
                .build());
    }

    @GetMapping("/repos/{id}/chat")
    public ResponseEntity<?> getChatHistory(@PathVariable Long id, HttpSession session) {
        User user = getAuthenticatedUser(session);
        AnalyzedRepo repo = repoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found with ID: " + id));

        if (!repo.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied to this repository analysis"));
        }

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
