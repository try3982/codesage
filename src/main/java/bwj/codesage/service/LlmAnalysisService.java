package bwj.codesage.service;

import bwj.codesage.client.GroqClient;
import bwj.codesage.domain.entity.AnalysisCategory;
import bwj.codesage.domain.entity.AnalysisJob;
import bwj.codesage.domain.entity.AnalysisResult;
import bwj.codesage.domain.entity.InterviewQuestion;
import bwj.codesage.domain.entity.Severity;
import bwj.codesage.domain.enums.InterviewRole;
import bwj.codesage.domain.enums.QuestionType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAnalysisService {

    private final GroqClient groqClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
        You are a senior software engineer performing a thorough code review.
        Analyze the provided code and return ONLY a valid JSON array.
        No explanation, no markdown, just raw JSON.
        
        Each item in the array must have:
        {
          "category": "ARCHITECTURE" | "CODE_QUALITY" | "SECURITY" | "PERFORMANCE",
          "severity": "INFO" | "WARNING" | "CRITICAL",
          "title": "short issue title (max 100 chars)",
          "description": "detailed explanation of the issue",
          "filePath": "path/to/file or null",
          "lineNumber": number or null,
          "suggestion": "how to fix this"
        }
        
        Focus on real, actionable issues. Max 10 items per analysis.
        """;

    public List<AnalysisResult> analyzeChunks(AnalysisJob job, String codeContext, String filePath) {
        String userPrompt = String.format("""
            Analyze this code from file: %s

            ```
            %s
            ```
            """, filePath, truncate(codeContext, 6000));

        try {
            String response = groqClient.chat(SYSTEM_PROMPT, userPrompt);
            return parseResults(job, response);
        } catch (Exception e) {
            log.error("LLM analysis failed for {}: {}", filePath, e.getMessage());
            return List.of();
        }
    }

    /**
     * 여러 파일을 한 번의 API 호출로 분석합니다 (배치 처리).
     * @param fileContents List of [filePath, content]
     */
    public List<AnalysisResult> batchAnalyze(AnalysisJob job, List<String[]> fileContents) {
        StringBuilder sb = new StringBuilder();
        for (String[] fc : fileContents) {
            sb.append("=== FILE: ").append(fc[0]).append(" ===\n");
            sb.append(truncate(fc[1], 1500)).append("\n\n");
        }

        String userPrompt = "Analyze these " + fileContents.size() + " files and return ALL issues found:\n\n" + sb;

        String response = groqClient.chat(SYSTEM_PROMPT, userPrompt);
        return parseResults(job, response);
    }

    public String generateSummary(AnalysisJob job, List<AnalysisResult> allResults) {
        String summaryPrompt = String.format("""
            Based on %d issues found in repository '%s/%s', write a concise executive summary.
            
            Issue breakdown:
            - CRITICAL: %d
            - WARNING: %d
            - INFO: %d
            
            Categories: ARCHITECTURE=%d, CODE_QUALITY=%d, SECURITY=%d, PERFORMANCE=%d
            
            Return a JSON object with:
            {
              "overallScore": 0-100,
              "architectureSummary": "2-3 sentences",
              "codeQualitySummary": "2-3 sentences",
              "securitySummary": "2-3 sentences",
              "performanceSummary": "2-3 sentences",
              "techStack": ["detected", "technologies"],
              "topIssues": ["top 5 issue titles"]
            }
            """,
                allResults.size(),
                job.getRepoOwner(), job.getRepoName(),
                countBySeverity(allResults, Severity.CRITICAL),
                countBySeverity(allResults, Severity.WARNING),
                countBySeverity(allResults, Severity.INFO),
                countByCategory(allResults, AnalysisCategory.ARCHITECTURE),
                countByCategory(allResults, AnalysisCategory.CODE_QUALITY),
                countByCategory(allResults, AnalysisCategory.SECURITY),
                countByCategory(allResults, AnalysisCategory.PERFORMANCE)
        );

        return groqClient.chat(
                "You are a technical report writer. Return only valid JSON.",
                summaryPrompt
        );
    }

    private List<AnalysisResult> parseResults(AnalysisJob job, String jsonResponse) {
        List<AnalysisResult> results = new ArrayList<>();
        try {
            // Strip markdown code blocks if present
            String clean = jsonResponse
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            List<Map<String, Object>> items = objectMapper.readValue(
                    clean, new TypeReference<>() {}
            );

            for (Map<String, Object> item : items) {
                try {
                    results.add(AnalysisResult.builder()
                            .job(job)
                            .category(AnalysisCategory.valueOf((String) item.get("category")))
                            .severity(Severity.valueOf((String) item.get("severity")))
                            .title((String) item.get("title"))
                            .description((String) item.get("description"))
                            .filePath((String) item.get("filePath"))
                            .lineNumber(item.get("lineNumber") instanceof Number n ? n.intValue() : null)
                            .suggestion((String) item.get("suggestion"))
                            .build());
                } catch (Exception e) {
                    log.warn("Failed to parse result item: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse LLM JSON response: {}", e.getMessage());
        }
        return results;
    }

    private static final String INTERVIEW_SYSTEM_PROMPT = """
        You are a senior technical interviewer. Based on the repository analysis, generate interview questions.
        Return ONLY a valid JSON array. No explanation, no markdown.

        Each item must have:
        {
          "type": "ARCH" | "CODE" | "SEC" | "PERF" | "DESIGN",
          "difficulty": 1 to 5 (integer, 1=easiest, 5=hardest),
          "question": "the interview question",
          "focus": "what skill/knowledge this tests (max 200 chars)",
          "modelAnswer": "ideal answer (3-5 sentences)",
          "whyBest": "why this answer demonstrates strong understanding"
        }

        Generate between 5 and 15 questions depending on the complexity and size of the repository.
        Simple repositories: 5-7 questions. Medium: 8-11 questions. Large/complex: 12-15 questions.
        Cover all relevant types (ARCH, CODE, SEC, PERF, DESIGN) proportional to the codebase.
        Mix difficulty levels naturally: mostly 2-3, some 4, occasionally 5 for senior/interviewer roles.
        """;

    public List<InterviewQuestion> generateInterviewQuestions(AnalysisJob job, InterviewRole role) {
        String roleName = role.name().toLowerCase();
        String userPrompt = String.format("""
            Repository: %s/%s
            Role being interviewed for: %s

            Generate technical interview questions based on this project's tech stack and architecture.
            Focus on questions a %s developer would face when discussing this project.
            """, job.getRepoOwner(), job.getRepoName(), roleName, roleName);

        try {
            String response = groqClient.chat(INTERVIEW_SYSTEM_PROMPT, userPrompt);
            return parseInterviewQuestions(job, role, response);
        } catch (Exception e) {
            log.error("Interview question generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    public String reviewAnswer(String question, String modelAnswer, String userAnswer) {
        String systemPrompt = """
                You are a technical interviewer reviewing a candidate's answer.
                Provide constructive feedback in 3-4 sentences.
                Mention what was good, what was missing, and give a score (1-10).
                Return plain text, not JSON.
                """;
        String userPrompt = String.format("""
                Question: %s

                Model Answer: %s

                Candidate's Answer: %s

                Please review the candidate's answer.
                """, question, modelAnswer, userAnswer);
        return groqClient.chat(systemPrompt, userPrompt);
    }

    private List<InterviewQuestion> parseInterviewQuestions(AnalysisJob job, InterviewRole role, String jsonResponse) {
        List<InterviewQuestion> questions = new ArrayList<>();
        try {
            String clean = jsonResponse.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
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
        return questions;
    }

    private QuestionType parseQuestionType(String value) {
        if (value == null) return QuestionType.CODE;
        try {
            return QuestionType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return QuestionType.CODE;
        }
    }

    private long countBySeverity(List<AnalysisResult> results, Severity severity) {
        return results.stream().filter(r -> r.getSeverity() == severity).count();
    }

    private long countByCategory(List<AnalysisResult> results, AnalysisCategory category) {
        return results.stream().filter(r -> r.getCategory() == category).count();
    }

    private String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
