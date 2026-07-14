package com.binocheck.service;

import com.binocheck.dto.GitHubRepoResponse;
import com.binocheck.dto.GitHubTreeResponse;
import com.binocheck.dto.RepoCoordinates;
import com.binocheck.dto.TreeEntry;
import com.binocheck.exception.GitHubApiException;
import com.binocheck.exception.InvalidGitHubUrlException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GitHubService {

    private final WebClient githubWebClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public GitHubService(@Qualifier("githubWebClient") WebClient githubWebClient, ObjectMapper objectMapper) {
        this.githubWebClient = githubWebClient;
        this.objectMapper = objectMapper;
    }

    public RepoCoordinates parseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidGitHubUrlException("GitHub URL cannot be empty");
        }
        String cleaned = url.trim();
        if (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }

        String marker = "github.com/";
        int index = cleaned.indexOf(marker);
        if (index == -1) {
            throw new InvalidGitHubUrlException("URL must be a valid GitHub repository URL");
        }

        String path = cleaned.substring(index + marker.length());
        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new InvalidGitHubUrlException("URL must contain both owner and repository name");
        }

        String owner = parts[0];
        String repo = parts[1];
        if (owner.isBlank() || repo.isBlank()) {
            throw new InvalidGitHubUrlException("Invalid owner or repository name in URL");
        }
        return new RepoCoordinates(owner, repo);
    }

    public GitHubRepoResponse fetchMetadata(String owner, String repo) {
        return this.githubWebClient.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .flatMap(body -> {
                            HttpStatus status = (HttpStatus) response.statusCode();
                            if (status == HttpStatus.NOT_FOUND) {
                                return Mono.error(new GitHubApiException(status, "GitHub repository not found: " + owner + "/" + repo));
                            }
                            return Mono.error(new GitHubApiException(status, "GitHub API error: " + body));
                        }))
                .bodyToMono(GitHubRepoResponse.class)
                .block();
    }

    public List<TreeEntry> fetchTree(String owner, String repo, String branch) {
        try {
            GitHubTreeResponse response = this.githubWebClient.get()
                    .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                            .flatMap(body -> Mono.error(new GitHubApiException((HttpStatus) r.statusCode(),
                                    "Failed to fetch file tree: " + body))))
                    .bodyToMono(GitHubTreeResponse.class)
                    .block();
            return response != null && response.getTree() != null ? response.getTree() : List.of();
        } catch (Exception e) {
            if (e instanceof GitHubApiException) {
                throw e;
            }
            throw new GitHubApiException(HttpStatus.BAD_GATEWAY, "Failed to connect to GitHub Trees API: " + e.getMessage());
        }
    }

    public String fetchFileContent(String owner, String repo, String path) {
        try {
            return this.githubWebClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.raw")
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> Mono.empty())
                    .block();
        } catch (Exception e) {
            return "";
        }
    }

    public String fetchReadme(String owner, String repo) {
        try {
            return this.githubWebClient.get()
                    .uri("/repos/{owner}/{repo}/readme", owner, repo)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.raw")
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> Mono.empty())
                    .block();
        } catch (Exception e) {
            return "";
        }
    }

    public Map<String, Object> collectRepositoryData(String url) {
        RepoCoordinates coords = parseUrl(url);
        String owner = coords.getOwner();
        String repo = coords.getRepo();

        GitHubRepoResponse metadata = fetchMetadata(owner, repo);
        String defaultBranch = metadata.getDefaultBranch() != null ? metadata.getDefaultBranch() : "main";

        List<TreeEntry> rawTree = fetchTree(owner, repo, defaultBranch);

        // Filter: Max depth of 3 levels, and max 150 files processed.
        List<TreeEntry> filteredTree = rawTree.stream()
                .filter(entry -> {
                    String path = entry.getPath();
                    int depth = path.split("/").length;
                    return depth <= 3;
                })
                .limit(150)
                .collect(Collectors.toList());

        // Fetch Readme content
        String readmeContent = fetchReadme(owner, repo);

        // Fetch key manifest files content
        Map<String, String> manifestContents = new HashMap<>();
        for (TreeEntry entry : filteredTree) {
            if ("blob".equals(entry.getType()) && isKeyManifestFile(entry.getPath())) {
                // Cap at 50 KB per file
                if (entry.getSize() != null && entry.getSize() <= 50000) {
                    String content = fetchFileContent(owner, repo, entry.getPath());
                    if (content != null && !content.isBlank()) {
                        manifestContents.put(entry.getPath(), content);
                    }
                }
            }
        }

        if (readmeContent == null) {
            readmeContent = "";
        }

        // Convert file tree to JSON
        String fileTreeJson = "";
        try {
            fileTreeJson = objectMapper.writeValueAsString(filteredTree);
        } catch (Exception e) {
            fileTreeJson = "[]";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("owner", owner);
        result.put("repoName", repo);
        result.put("metadata", metadata);
        result.put("fileTreeList", filteredTree);
        result.put("fileTreeJson", fileTreeJson);
        result.put("readme", readmeContent);
        result.put("manifestContents", manifestContents);

        return result;
    }

    private boolean isKeyManifestFile(String path) {
        if (path == null) return false;
        String filename = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
        return filename.equals("pom.xml")
                || filename.equals("build.gradle")
                || filename.equals("package.json")
                || filename.equals("requirements.txt")
                || filename.equals("pyproject.toml")
                || filename.equals("go.mod")
                || filename.equals("cargo.toml")
                || filename.equals("composer.json")
                || filename.equals("gemfile")
                || filename.equals("dockerfile")
                || filename.equals("docker-compose.yml");
    }
}
