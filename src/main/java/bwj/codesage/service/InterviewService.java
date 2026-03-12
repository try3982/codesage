package bwj.codesage.service;

import bwj.codesage.client.GeminiClient;
import bwj.codesage.exception.JobNotFoundException;
import bwj.codesage.domain.entity.AnalysisJob;
import bwj.codesage.domain.entity.AnalysisResult;
import bwj.codesage.domain.entity.AnalysisSummary;
import bwj.codesage.domain.entity.AnswerReview;
import bwj.codesage.domain.entity.InterviewQuestion;
import bwj.codesage.domain.enums.InterviewRole;
import bwj.codesage.domain.enums.QuestionType;
import bwj.codesage.dto.response.AnswerReviewResponse;
import bwj.codesage.dto.response.InterviewQuestionResponse;
import bwj.codesage.repository.AnalysisJobRepository;
import bwj.codesage.repository.AnalysisResultRepository;
import bwj.codesage.repository.AnalysisSummaryRepository;
import bwj.codesage.repository.AnswerReviewRepository;
import bwj.codesage.repository.InterviewQuestionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final AnalysisJobRepository jobRepository;
    private final AnalysisResultRepository resultRepository;
    private final AnalysisSummaryRepository summaryRepository;
    private final InterviewQuestionRepository questionRepository;
    private final AnswerReviewRepository answerReviewRepository;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<InterviewQuestionResponse> generateQuestions(UUID jobId, String role) {
        InterviewRole interviewRole = parseRole(role);

        // 이미 생성된 질문이 있으면 재생성 없이 반환
        List<InterviewQuestion> existing = questionRepository.findByJobIdAndRole(jobId, interviewRole);
        if (!existing.isEmpty()) {
            log.info("Returning {} existing questions for job={} role={}", existing.size(), jobId, interviewRole);
            return existing.stream().map(InterviewQuestionResponse::from).toList();
        }

        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        AnalysisSummary summary = summaryRepository.findByJob(job).orElse(null);

        // severity 기준 상위 5개 (CRITICAL → WARNING → INFO)
        List<AnalysisResult> topResults = resultRepository.findByJob(job).stream()
                .sorted(Comparator.comparingInt(r -> severityOrder(r.getSeverity().name())))
                .limit(5)
                .toList();

        String contextJson = buildContextJson(job, summary, topResults, interviewRole);
        log.info("Generating interview questions for job={} role={}", jobId, interviewRole);

        String response = geminiClient.generateInterviewQuestions(contextJson);
        List<InterviewQuestion> questions = parseAndSave(job, interviewRole, response);

        return questions.stream().map(InterviewQuestionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<InterviewQuestionResponse> getQuestions(UUID jobId, String role) {
        InterviewRole interviewRole = parseRole(role);
        return questionRepository.findByJobIdAndRole(jobId, interviewRole).stream()
                .map(InterviewQuestionResponse::from)
                .toList();
    }

    @Transactional
    public AnswerReviewResponse reviewAnswer(UUID questionId, String userAnswer) {
        InterviewQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new JobNotFoundException("Question not found: " + questionId));

        AnalysisJob job = question.getJob();
        AnalysisSummary summary = summaryRepository.findByJob(job).orElse(null);
        String repoContext = buildRepoContext(job, summary);

        String jsonResponse = geminiClient.reviewAnswer(question.getQuestion(), userAnswer, repoContext);
        AnswerReview review = parseAndSaveReview(question, userAnswer, jsonResponse);
        return AnswerReviewResponse.from(review);
    }

    private AnswerReview parseAndSaveReview(InterviewQuestion question, String userAnswer, String jsonResponse) {
        try {
            String clean = jsonResponse
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            Map<String, Object> map = objectMapper.readValue(clean, new TypeReference<>() {});

            Integer score = map.get("score") instanceof Number n ? n.intValue() : null;
            String scoreLabel = (String) map.get("scoreLabel");
            String improvements = null;
            if (map.get("improvements") instanceof List<?> list) {
                improvements = objectMapper.writeValueAsString(list);
            }

            AnswerReview review = AnswerReview.builder()
                    .question(question)
                    .userAnswer(userAnswer)
                    .score(score)
                    .scoreLabel(scoreLabel)
                    .improvements(improvements)
                    .bestAnswer((String) map.get("bestAnswer"))
                    .whyBest((String) map.get("whyBest"))
                    .build();
            return answerReviewRepository.save(review);
        } catch (Exception e) {
            log.error("Failed to parse review JSON: {}", e.getMessage());
            // 파싱 실패 시 raw 응답을 bestAnswer에 저장
            AnswerReview review = AnswerReview.builder()
                    .question(question)
                    .userAnswer(userAnswer)
                    .bestAnswer(jsonResponse)
                    .build();
            return answerReviewRepository.save(review);
        }
    }

    private String buildRepoContext(AnalysisJob job, AnalysisSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append(job.getRepoOwner()).append("/").append(job.getRepoName());
        if (summary != null) {
            if (summary.getTechStack() != null) sb.append(", techStack=").append(summary.getTechStack());
            if (summary.getOverallScore() != null) sb.append(", score=").append(summary.getOverallScore());
        }
        return sb.toString();
    }

    private String buildContextJson(AnalysisJob job, AnalysisSummary summary,
                                    List<AnalysisResult> topResults, InterviewRole role) {
        try {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("repoName", job.getRepoOwner() + "/" + job.getRepoName());
            ctx.put("role", role.name().toLowerCase());
            if (summary != null) {
                ctx.put("overallScore", summary.getOverallScore());
                ctx.put("techStack", summary.getTechStack());
                ctx.put("topIssues", summary.getTopIssues());
            }
            List<Map<String, String>> issues = new ArrayList<>();
            for (AnalysisResult r : topResults) {
                Map<String, String> issue = new LinkedHashMap<>();
                issue.put("severity", r.getSeverity().name());
                issue.put("category", r.getCategory().name());
                issue.put("title", r.getTitle());
                issues.add(issue);
            }
            ctx.put("recentIssues", issues);
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            log.warn("Failed to serialize context JSON: {}", e.getMessage());
            return "{\"repoName\":\"" + job.getRepoOwner() + "/" + job.getRepoName()
                    + "\",\"role\":\"" + role.name().toLowerCase() + "\"}";
        }
    }

    private List<InterviewQuestion> parseAndSave(AnalysisJob job, InterviewRole role, String jsonResponse) {
        List<InterviewQuestion> questions = new ArrayList<>();
        try {
            String clean = jsonResponse
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            List<Map<String, Object>> items = objectMapper.readValue(clean, new TypeReference<>() {});
            for (Map<String, Object> item : items) {
                try {
                    QuestionType type = parseQuestionType((String) item.get("type"));
                    int difficulty = item.get("difficulty") instanceof Number n ? n.intValue() : 3;
                    String focus = truncate((String) item.get("focus"), 200);

                    questions.add(InterviewQuestion.builder()
                            .job(job)
                            .role(role)
                            .type(type)
                            .difficulty(difficulty)
                            .question((String) item.get("question"))
                            .focus(focus)
                            .modelAnswer((String) item.get("modelAnswer"))
                            .whyBest((String) item.get("whyBest"))
                            .build());
                } catch (Exception e) {
                    log.warn("Failed to parse interview question item: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse interview questions JSON: {}", e.getMessage());
        }
        return questionRepository.saveAll(questions);
    }

    private InterviewRole parseRole(String role) {
        if (role == null || role.isBlank()) return InterviewRole.JUNIOR;
        try {
            return InterviewRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return InterviewRole.JUNIOR;
        }
    }

    private QuestionType parseQuestionType(String value) {
        if (value == null) return QuestionType.CODE;
        try {
            return QuestionType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return QuestionType.CODE;
        }
    }

    private int severityOrder(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "WARNING" -> 1;
            default -> 2;
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
