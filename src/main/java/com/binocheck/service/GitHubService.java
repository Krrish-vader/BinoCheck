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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GitHubService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GitHubService.class);

    // How many manifest files are requested from GitHub at the same time.
    private static final int MANIFEST_FETCH_CONCURRENCY = 6;

    private final WebClient githubWebClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public GitHubService(@Qualifier("githubWebClient") WebClient githubWebClient, ObjectMapper objectMapper) {
        this.githubWebClient = githubWebClient;
        this.objectMapper = objectMapper;
    }

    // github.com/<owner>/<repo>, optionally followed by .git, a trailing slash, or a sub-path like /tree/main.
    // Owner and repo names are limited to the characters GitHub itself allows.
    private static final java.util.regex.Pattern GITHUB_REPO_URL = java.util.regex.Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?(?:[/?#].*)?$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    public RepoCoordinates parseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidGitHubUrlException("GitHub URL cannot be empty");
        }
        java.util.regex.Matcher m = GITHUB_REPO_URL.matcher(url.trim());
        if (!m.matches() || m.group(2).equals(".") || m.group(2).equals("..")) {
            throw new InvalidGitHubUrlException("Please enter a GitHub repository URL like https://github.com/owner/repo");
        }
        return new RepoCoordinates(m.group(1), m.group(2));
    }

    /** One canonical form per repository, so the same repo is never analyzed (and billed) twice. */
    public String canonicalUrl(RepoCoordinates coords) {
        return ("https://github.com/" + coords.getOwner() + "/" + coords.getRepo()).toLowerCase(java.util.Locale.ROOT);
    }

    public GitHubRepoResponse fetchMetadata(String owner, String repo) {
        try {
            return this.githubWebClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(toClientError(response.statusCode(), body,
                                    "GitHub repository not found: " + owner + "/" + repo))))
                    .bodyToMono(GitHubRepoResponse.class)
                    .block();
        } catch (GitHubApiException e) {
            throw e;
        } catch (Exception e) {
            // Timeouts and connection failures: logged here, reported to the user in plain words.
            log.warn("Failed to fetch GitHub metadata for {}/{}", owner, repo, e);
            throw new GitHubApiException(HttpStatus.BAD_GATEWAY, "GitHub could not be reached. Please try again later.");
        }
    }

    /**
     * Converts a GitHub error into one that is safe to show to the user. GitHub's own status is not passed
     * through: a 401/403 from GitHub (bad token, rate limit) must not look like "you are logged out" to the
     * frontend, and GitHub's response body is logged rather than returned.
     */
    private GitHubApiException toClientError(HttpStatusCode status, String body, String notFoundMessage) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return new GitHubApiException(HttpStatus.NOT_FOUND, notFoundMessage);
        }
        log.warn("GitHub API returned {}: {}", status.value(), body);
        if (status.value() == HttpStatus.FORBIDDEN.value() || status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new GitHubApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub rate limit reached. Please try again in a few minutes.");
        }
        return new GitHubApiException(HttpStatus.BAD_GATEWAY, "GitHub could not be reached. Please try again later.");
    }

    public List<TreeEntry> fetchTree(String owner, String repo, String branch) {
        try {
            GitHubTreeResponse response = this.githubWebClient.get()
                    .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(toClientError(r.statusCode(), body,
                                    "Could not read the file tree of " + owner + "/" + repo + "."))))
                    .bodyToMono(GitHubTreeResponse.class)
                    .block();
            return response != null && response.getTree() != null ? response.getTree() : List.of();
        } catch (Exception e) {
            if (e instanceof GitHubApiException) {
                throw e;
            }
            log.warn("Failed to fetch GitHub tree for {}/{}", owner, repo, e);
            throw new GitHubApiException(HttpStatus.BAD_GATEWAY, "GitHub could not be reached. Please try again later.");
        }
    }

    public String fetchFileContent(String owner, String repo, String path) {
        try {
            return fileContent(owner, repo, path).block();
        } catch (Exception e) {
            return "";
        }
    }

    /** Emits the file's text, or completes empty when the file can't be read. */
    private Mono<String> fileContent(String owner, String repo, String path) {
        return this.githubWebClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.raw")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Fetches several files at once instead of one after another. An unreadable or blank file is
     * left out. The result keeps the order of {@code paths}, so the prompt built from it is stable.
     */
    Map<String, String> fetchFileContents(String owner, String repo, List<String> paths) {
        Map<String, String> contents = new LinkedHashMap<>();
        List<Map.Entry<String, String>> fetched = Flux.fromIterable(paths)
                .flatMapSequential(path -> fileContent(owner, repo, path)
                        .filter(content -> !content.isBlank())
                        .map(content -> Map.entry(path, content)), MANIFEST_FETCH_CONCURRENCY)
                .collectList()
                .block();
        if (fetched != null) {
            fetched.forEach(entry -> contents.put(entry.getKey(), entry.getValue()));
        }
        return contents;
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
        List<String> manifestPaths = filteredTree.stream()
                .filter(entry -> "blob".equals(entry.getType()) && isKeyManifestFile(entry.getPath()))
                // Cap at 50 KB per file
                .filter(entry -> entry.getSize() != null && entry.getSize() <= 50000)
                .map(TreeEntry::getPath)
                .collect(Collectors.toList());
        Map<String, String> manifestContents = fetchFileContents(owner, repo, manifestPaths);

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
