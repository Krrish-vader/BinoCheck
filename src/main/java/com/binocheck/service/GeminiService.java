package com.binocheck.service;

import com.binocheck.dto.GitHubRepoResponse;
import com.binocheck.dto.TreeEntry;
import com.binocheck.entity.ChatMessage;
import com.binocheck.exception.GeminiApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GeminiService {

    private final WebClient geminiWebClient;
    private final ObjectMapper objectMapper;
    private final String modelName;

    @Autowired
    public GeminiService(@Qualifier("geminiWebClient") WebClient geminiWebClient, 
                         ObjectMapper objectMapper,
                         @Value("${app.gemini.model}") String modelName) {
        this.geminiWebClient = geminiWebClient;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    public String analyzeRepository(Map<String, Object> repoData) {
        String owner = (String) repoData.get("owner");
        String repoName = (String) repoData.get("repoName");
        GitHubRepoResponse metadata = (GitHubRepoResponse) repoData.get("metadata");
        List<TreeEntry> fileTree = (List<TreeEntry>) repoData.get("fileTreeList");
        String readme = (String) repoData.get("readme");
        Map<String, String> manifestContents = (Map<String, String>) repoData.get("manifestContents");

        // Format file tree representation
        String fileTreeStr = fileTree.stream()
                .map(e -> String.format("- %s (%s)", e.getPath(), e.getType()))
                .collect(Collectors.joining("\n"));

        // Format manifest file contents
        StringBuilder manifestsStr = new StringBuilder();
        manifestContents.forEach((path, content) -> {
            manifestsStr.append(String.format("\n--- FILE: %s ---\n", path));
            manifestsStr.append(content);
            manifestsStr.append("\n");
        });

        String promptText = String.format(
                "You are an expert software architect. Analyze the following GitHub repository and provide details on its tech stack, high-level architecture, module structure, and a detailed analysis of entry points and interactions.\n\n" +
                        "Repository: %s/%s\n" +
                        "Description: %s\n" +
                        "Primary Language: %s\n\n" +
                        "--- FILE TREE (Capped to 3 levels, 150 files) ---\n%s\n\n" +
                        "--- README.md ---\n%s\n\n" +
                        "--- KEY MANIFEST/CONFIGURATION FILES ---\n%s\n\n" +
                        "Respond ONLY in JSON matching the requested schema. Provide an accurate and comprehensive review.",
                owner, repoName,
                metadata.getDescription() != null ? metadata.getDescription() : "No description provided.",
                metadata.getLanguage() != null ? metadata.getLanguage() : "Not specified",
                fileTreeStr, readme, manifestsStr.toString()
        );

        // Build Response Schema
        Map<String, Object> techStackSchema = Map.of(
                "type", "ARRAY",
                "items", Map.of("type", "STRING"),
                "description", "List of technologies, frameworks, and programming languages identified in the repository."
        );
        Map<String, Object> architectureSummarySchema = Map.of(
                "type", "STRING",
                "description", "High-level summary of the architecture and design patterns of the repository."
        );
        Map<String, Object> moduleEntrySchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "name", Map.of("type", "STRING", "description", "Module or folder path"),
                        "purpose", Map.of("type", "STRING", "description", "What this module does")
                ),
                "required", List.of("name", "purpose")
        );
        Map<String, Object> moduleStructureSchema = Map.of(
                "type", "ARRAY",
                "items", moduleEntrySchema,
                "description", "Explanation of major directories and files in the repository."
        );
        Map<String, Object> detailedAnalysisSchema = Map.of(
                "type", "STRING",
                "description", "In-depth explanation of the codebase structure, entry points, and component interactions, formatted as markdown."
        );

        Map<String, Object> responseSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "techStack", techStackSchema,
                        "architectureSummary", architectureSummarySchema,
                        "moduleStructure", moduleStructureSchema,
                        "detailedAnalysis", detailedAnalysisSchema
                ),
                "required", List.of("techStack", "architectureSummary", "moduleStructure", "detailedAnalysis")
        );

        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema
        );

        Map<String, Object> requestPayload = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", promptText)))
                ),
                "generationConfig", generationConfig
        );

        return callGemini(requestPayload);
    }

    public String generateChatResponse(String repoContext, List<ChatMessage> history, String userQuestion) {
        String systemInstructionText = "You are Binocheck, an expert AI coding assistant. You are helping the user understand the codebase of the analyzed repository. Below is the codebase context (metadata, file tree, key file contents, and initial analysis). Answer the user's follow-up questions accurately based on this context. Keep answers clear, technical, and precise. If they ask about code that is not shown, answer based on common architectural patterns of the technologies involved or suggest where it would be located.";

        // Build list of contents
        List<Map<String, Object>> contents = new ArrayList<>();

        // Inject repoContext as the first message or prepend it to the first user question
        // Let's prepend the context to the first message if history is empty,
        // or just supply it as system instruction/system context.
        // Actually, supplying repoContext in the first turn is great. Let's do that!
        
        if (history.isEmpty()) {
            String firstUserText = String.format("Repository Context:\n%s\n\nQuestion: %s", repoContext, userQuestion);
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", firstUserText))));
        } else {
            // Re-compile history
            boolean first = true;
            for (ChatMessage msg : history) {
                String role = "USER".equalsIgnoreCase(msg.getRole()) ? "user" : "model";
                String text = msg.getMessageText();
                if (first && "user".equals(role)) {
                    text = String.format("Repository Context:\n%s\n\nQuestion: %s", repoContext, text);
                    first = false;
                }
                contents.add(Map.of("role", role, "parts", List.of(Map.of("text", text))));
            }
            // Append current question
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userQuestion))));
        }

        Map<String, Object> requestPayload = Map.of(
                "contents", contents,
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstructionText)))
        );

        return callGemini(requestPayload);
    }

    private String callGemini(Map<String, Object> payload) {
        try {
            String responseJson = this.geminiWebClient.post()
                    .uri("/v1beta/models/{model}:generateContent", modelName)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                            .flatMap(body -> {
                                HttpStatus status = (HttpStatus) response.statusCode();
                                if (status == HttpStatus.NOT_FOUND) {
                                    return Mono.error(new GeminiApiException(
                                            "AI model unavailable — configuration issue. The model '" + modelName + 
                                            "' may be deprecated or the API endpoint is incorrect. Details: " + body));
                                }
                                return Mono.error(new GeminiApiException("Gemini API returned error status " + status.value() + ": " + body));
                            }))
                    .bodyToMono(String.class)
                    .block();

            // Extract the generated text from candidates[0].content.parts[0].text
            Map<String, Object> responseMap = objectMapper.readValue(responseJson, Map.class);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new GeminiApiException("Gemini API response did not contain candidates.");
            }
            Map<String, Object> candidate = candidates.get(0);
            Map<String, Object> content = (Map<String, Object>) candidate.get("content");
            if (content == null) {
                throw new GeminiApiException("Gemini API candidate did not contain content.");
            }
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) {
                throw new GeminiApiException("Gemini API content did not contain parts.");
            }
            String text = (String) parts.get(0).get("text");
            if (text == null) {
                throw new GeminiApiException("Gemini API response text was null.");
            }
            return text;
        } catch (Exception e) {
            if (e instanceof GeminiApiException) {
                throw (GeminiApiException) e;
            }
            throw new GeminiApiException("Failed to communicate with Gemini API: " + e.getMessage(), e);
        }
    }
}
