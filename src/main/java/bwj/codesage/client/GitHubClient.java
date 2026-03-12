package bwj.codesage.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class GitHubClient {

    private final WebClient webClient;

    @Value("${github.api-token}")
    private String apiToken;

    public GitHubClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @SuppressWarnings("unchecked")
    public List<String> getRepoFilePaths(String owner, String repo, String ref) {
        String uri = String.format(
                "https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1",
                owner, repo, ref != null ? ref : "HEAD"
        );

        try {
            Map<String, Object> response = webClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) {
                log.warn("GitHub tree API returned null for {}/{}", owner, repo);
                return List.of();
            }

            List<Map<String, Object>> tree = (List<Map<String, Object>>) response.get("tree");
            if (tree == null) {
                return List.of();
            }

            return tree.stream()
                    .filter(entry -> "blob".equals(entry.get("type")))
                    .map(entry -> (String) entry.get("path"))
                    .toList();

        } catch (WebClientResponseException e) {
            log.error("GitHub tree API error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("GitHub API error: " + e.getStatusCode(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<String> getFileContent(String owner, String repo, String ref, String filePath) {
        String uri = String.format(
                "https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                owner, repo, filePath, ref != null ? ref : "HEAD"
        );

        try {
            Map<String, Object> response = webClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return Optional.empty();

            String encodedContent = (String) response.get("content");
            if (encodedContent == null) return Optional.empty();

            String cleaned = encodedContent.replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(cleaned);
            return Optional.of(new String(decoded));

        } catch (WebClientResponseException e) {
            log.warn("GitHub content API error for {}: {} {}", filePath, e.getStatusCode(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to fetch file content for {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }
}
