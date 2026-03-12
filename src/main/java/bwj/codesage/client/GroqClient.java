package bwj.codesage.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GroqClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    private static final String BASE_URL = "https://api.groq.com/openai/v1/chat/completions";

    public GroqClient(
            WebClient.Builder webClientBuilder,
            @Value("${groq.api-key}") String apiKey,
            @Value("${groq.model:llama-3.3-70b-versatile}") String model
    ) {
        this.webClient = webClientBuilder.build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @SuppressWarnings("unchecked")
    public String chat(String systemPrompt, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("User prompt must not be null or blank");
        }

        var messages = systemPrompt != null && !systemPrompt.isBlank()
                ? List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt))
                : List.of(Map.of("role", "user", "content", userPrompt));

        var requestBody = Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.2,
                "max_tokens", 4000
        );

        try {
            var response = webClient.post()
                    .uri(BASE_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) throw new RuntimeException("Groq response is null");

            var choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) throw new RuntimeException("Groq response has no choices");

            var message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) throw new RuntimeException("Groq response has no message");

            String content = (String) message.get("content");
            if (content == null) throw new RuntimeException("Groq response content is null");
            return content;

        } catch (WebClientResponseException e) {
            log.error("Groq API error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("429: Groq API error: " + e.getStatusCode(), e);
        }
    }
}
