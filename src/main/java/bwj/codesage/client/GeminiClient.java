package bwj.codesage.client;

import bwj.codesage.exception.GeminiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String baseUrl;
    private final String embeddingBaseUrl;
    private final String embeddingModel;
    private final String chatModel;

    private static final int[] RETRY_DELAYS_MS = {4000, 8000, 16000};

    public GeminiClient(
            WebClient.Builder webClientBuilder,
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.base-url}") String baseUrl,
            @Value("${gemini.embedding-base-url}") String embeddingBaseUrl,
            @Value("${gemini.embedding-model}") String embeddingModel,
            @Value("${gemini.chat-model}") String chatModel
    ) {
        this.webClient = webClientBuilder.build();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
    }

    /**
     * 텍스트를 Gemini 임베딩 모델로 벡터화합니다.
     *
     * @param text 임베딩할 텍스트 (null·blank 불가)
     * @return float[] 임베딩 벡터 (text-embedding-004 기준 768차원)
     * @throws IllegalArgumentException text가 null이거나 blank일 때
     * @throws GeminiException          API 오류 또는 응답 파싱 실패 시
     */
    @SuppressWarnings("unchecked")
    public float[] createEmbedding(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text must not be null or blank");
        }

        var requestBody = Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of("parts", List.of(Map.of("text", text)))
        );
        String uri = embeddingBaseUrl + "/models/" + embeddingModel + ":embedContent?key=" + apiKey;

        try {
            var response = webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new GeminiException("Gemini embedding 4xx error " + clientResponse.statusCode() + ": " + body)))
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .map(body -> new GeminiException("Gemini embedding 5xx error " + clientResponse.statusCode() + ": " + body)))
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) {
                throw new GeminiException("Gemini embedding response is null");
            }

            var embedding = (Map<String, Object>) response.get("embedding");
            if (embedding == null) {
                throw new GeminiException("Gemini embedding response missing 'embedding' field");
            }

            var values = (List<Double>) embedding.get("values");
            if (values == null || values.isEmpty()) {
                throw new GeminiException("Gemini embedding 'values' is null or empty");
            }

            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i).floatValue();
            }
            return result;

        } catch (WebClientResponseException e) {
            log.error("Gemini embedding API error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new GeminiException("Gemini embedding API error: " + e.getStatusCode(), e);
        }
    }

    /**
     * 시스템 프롬프트와 사용자 프롬프트로 Gemini 챗봇 응답을 생성합니다.
     *
     * @param systemPrompt 시스템 지시문 (null이면 빈 문자열로 처리)
     * @param userPrompt   사용자 입력 (null·blank 불가)
     * @return 모델 응답 텍스트
     * @throws IllegalArgumentException userPrompt가 null이거나 blank일 때
     * @throws GeminiException          API 오류 또는 응답 파싱 실패 시
     */
    @SuppressWarnings("unchecked")
    public String chat(String systemPrompt, String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("User prompt must not be null or blank");
        }

        String resolvedSystem = (systemPrompt != null) ? systemPrompt : "";
        var requestBody = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", resolvedSystem))),
                "contents", List.of(
                        Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))
                ),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "maxOutputTokens", 2000
                )
        );
        String uri = baseUrl + "/models/" + chatModel + ":generateContent?key=" + apiKey;

        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            try {
                return doChatCall(requestBody, uri);
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().value() == 429 && attempt < RETRY_DELAYS_MS.length) {
                    int delayMs = RETRY_DELAYS_MS[attempt];
                    log.warn("Gemini rate limit (429), retrying in {}ms (attempt {}/{})",
                            delayMs, attempt + 1, RETRY_DELAYS_MS.length);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new GeminiException("Interrupted during rate limit backoff", ie);
                    }
                } else {
                    log.error("Gemini chat API error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                    throw new GeminiException("Gemini chat API error: " + e.getStatusCode(), e);
                }
            }
        }
        throw new GeminiException("Gemini chat failed after " + RETRY_DELAYS_MS.length + " retries");
    }

    private static final String INTERVIEW_SYSTEM_PROMPT = """
            You are a senior technical interviewer.
            Based on the repository context provided, generate exactly 5 interview questions.
            Return ONLY a valid JSON array. No explanation, no markdown.

            Each item must have:
            {
              "type": "ARCH" | "CODE" | "SEC" | "PERF" | "DESIGN",
              "difficulty": 1 to 5 (integer),
              "question": "the interview question",
              "focus": "what skill/knowledge this tests (max 200 chars)",
              "modelAnswer": "ideal answer (3-5 sentences)",
              "whyBest": "why this answer demonstrates strong understanding"
            }

            Tailor difficulty and focus to the role:
            - JUNIOR: basic implementation reasoning, technology choices
            - SENIOR: architecture decisions, trade-offs, scalability
            - INTERVIEWER: sharp, candidate-probing questions that reveal depth

            Generate 5 questions: 2 with difficulty 2, 2 with difficulty 3, 1 with difficulty 4.
            """;

    /**
     * 저장소 컨텍스트 JSON 기반으로 면접 질문 5개를 생성합니다.
     *
     * @param contextJson { repoName, techStack, overallScore, topIssues, role } JSON 문자열
     * @return Gemini 응답 텍스트 (JSON 배열)
     */
    public String generateInterviewQuestions(String contextJson) {
        return chat(INTERVIEW_SYSTEM_PROMPT, "Repository context:\n" + contextJson);
    }

    private static final String REVIEW_SYSTEM_PROMPT = """
            You are a strict but constructive technical interviewer reviewing a candidate's answer.
            Return ONLY a valid JSON object. No explanation, no markdown.

            {
              "score": 1 to 100 (integer, omit or set null if userAnswer is empty/too short),
              "scoreLabel": "one-line evaluation in Korean",
              "improvements": ["improvement point 1", "improvement point 2"],
              "bestAnswer": "the ideal answer a top candidate would give (3-5 sentences)",
              "whyBest": "why this answer demonstrates strong understanding (2-3 sentences)"
            }

            If the userAnswer is empty or fewer than 20 characters, skip score/scoreLabel/improvements
            and return only bestAnswer and whyBest.
            """;

    /**
     * 사용자의 면접 답변을 리뷰합니다.
     *
     * @param question    면접 질문 텍스트
     * @param userAnswer  사용자 답변 텍스트 (비어있을 수 있음)
     * @param repoContext 저장소 컨텍스트 (repoName, techStack 등)
     * @return Gemini 응답 JSON 텍스트
     */
    public String reviewAnswer(String question, String userAnswer, String repoContext) {
        String prompt = String.format("""
                Repository context: %s

                Interview question: %s

                Candidate's answer: %s
                """, repoContext, question, userAnswer != null ? userAnswer : "(no answer provided)");
        return chat(REVIEW_SYSTEM_PROMPT, prompt);
    }

    @SuppressWarnings("unchecked")
    private String doChatCall(Object requestBody, String uri) {
        var response = webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .map(body -> new WebClientResponseException(
                                        clientResponse.statusCode().value(), body, null, null, null)))
                .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .map(body -> new WebClientResponseException(
                                        clientResponse.statusCode().value(), body, null, null, null)))
                .bodyToMono(Map.class)
                .block();

        if (response == null) throw new GeminiException("Gemini chat response is null");

        var candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty())
            throw new GeminiException("Gemini chat response has no candidates");

        var content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null)
            throw new GeminiException("Gemini chat response candidate has no 'content'");

        var parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty())
            throw new GeminiException("Gemini chat response content has no 'parts'");

        String text = (String) parts.get(0).get("text");
        if (text == null) throw new GeminiException("Gemini chat response 'text' is null");
        return text;
    }
}
