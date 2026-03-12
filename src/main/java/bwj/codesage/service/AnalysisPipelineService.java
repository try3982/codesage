package bwj.codesage.service;

import bwj.codesage.client.GitHubClient;
import bwj.codesage.client.GeminiClient;
import bwj.codesage.domain.entity.AnalysisJob;
import bwj.codesage.domain.entity.AnalysisResult;
import bwj.codesage.domain.entity.AnalysisSummary;
import bwj.codesage.domain.entity.CodeChunk;
import bwj.codesage.repository.AnalysisJobRepository;
import bwj.codesage.repository.AnalysisResultRepository;
import bwj.codesage.repository.AnalysisSummaryRepository;
import bwj.codesage.repository.CodeChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisPipelineService {

    private final GitHubClient gitHubClient;
    private final GeminiClient geminiClient;
    private final CodeChunkingService chunkingService;
    private final LlmAnalysisService llmAnalysisService;

    private final AnalysisJobRepository jobRepository;
    private final AnalysisResultRepository resultRepository;
    private final AnalysisSummaryRepository summaryRepository;
    private final CodeChunkRepository chunkRepository;
    private final JobProgressService jobProgressService;

    private final ObjectMapper objectMapper;

    @Value("${analysis.max-files:100}")
    private int maxFiles;

    @Value("${analysis.max-file-size-kb:100}")
    private int maxFileSizeKb;

    @Value("${gemini.rate-limit-delay-ms:500}")
    private long rateLimitDelayMs;

    @Async
    @Transactional
    public void runAnalysis(UUID jobId) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        log.info("Starting analysis for job {} - repo: {}/{}", jobId, job.getRepoOwner(), job.getRepoName());

        try {
            job.markInProgress();
            jobRepository.save(job);

            // Step 1: Get file list
            List<String> allFiles = gitHubClient.getRepoFilePaths(
                    job.getRepoOwner(), job.getRepoName(), job.getCommitHash()
            );

            List<String> supportedFiles = allFiles.stream()
                    .filter(chunkingService::isSupportedFile)
                    .sorted(Comparator.comparingInt(chunkingService::getFilePriority))
                    .toList();

            List<String> targetFiles = supportedFiles.stream().limit(maxFiles).toList();
            int skipped = supportedFiles.size() - targetFiles.size();

            job.setTotalFiles(targetFiles.size());
            job.setSkippedFiles(skipped);
            if (skipped > 0) {
                log.info("Job {} - {} files skipped (limit: {})", jobId, skipped, maxFiles);
            }
            jobRepository.save(job);

            log.info("Job {} - analyzing {} files", jobId, targetFiles.size());

            // Step 2: Process each file
            List<AnalysisResult> allResults = new ArrayList<>();

            for (int i = 0; i < targetFiles.size(); i++) {
                String filePath = targetFiles.get(i);
                try {
                    // 분석 시작 전 현재 파일명 즉시 커밋 (REQUIRES_NEW)
                    jobProgressService.updateProgress(job.getId(), i, filePath);

                    processFile(job, filePath, allResults);

                    // 분석 완료 후 진행 카운트 즉시 커밋 (REQUIRES_NEW)
                    jobProgressService.updateProgress(job.getId(), i + 1, null);

                    Thread.sleep(rateLimitDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Failed to process file {}: {}", filePath, e.getMessage());
                }
            }

            // Step 3: Save all results
            resultRepository.saveAll(allResults);

            // Step 4: Generate summary
            generateAndSaveSummary(job, allResults);

            // Step 5: Mark done
            job.markDone();
            jobRepository.save(job);

            log.info("Job {} completed with {} issues found", jobId, allResults.size());

        } catch (Exception e) {
            log.error("Analysis pipeline failed for job {}: {}", jobId, e.getMessage(), e);
            job.markFailed(e.getMessage());
            jobRepository.save(job);
        }
    }

    private void processFile(AnalysisJob job, String filePath, List<AnalysisResult> allResults) {
        Optional<String> contentOpt = gitHubClient.getFileContent(
                job.getRepoOwner(), job.getRepoName(), job.getCommitHash(), filePath
        );

        if (contentOpt.isEmpty()) return;

        String content = contentOpt.get();

        // Skip large files
        if (content.length() > maxFileSizeKb * 1024) {
            log.debug("Skipping large file: {}", filePath);
            return;
        }

        String language = chunkingService.detectLanguage(filePath);
        List<String> chunks = chunkingService.chunk(content);

        // Embed and store chunks
        for (int i = 0; i < chunks.size(); i++) {
            String chunkContent = chunks.get(i);
            try {
                float[] embedding = geminiClient.createEmbedding(chunkContent);

                CodeChunk codeChunk = CodeChunk.builder()
                        .job(job)
                        .filePath(filePath)
                        .language(language)
                        .content(chunkContent)
                        .chunkIndex(i)
                        .embedding(embedding)
                        .build();

                chunkRepository.save(codeChunk);
            } catch (Exception e) {
                log.warn("Embedding failed for chunk {}/{}: {}", filePath, i, e.getMessage());
            }
        }

        // Analyze file with LLM (use full content, not chunks)
        List<AnalysisResult> fileResults = analyzeWithRetry(job, content, filePath);
        allResults.addAll(fileResults);
    }

    private List<AnalysisResult> analyzeWithRetry(AnalysisJob job, String content, String filePath) {
        try {
            return llmAnalysisService.analyzeChunks(job, content, filePath);
        } catch (Exception e) {
            log.warn("Analysis failed for {}, retrying in 3s: {}", filePath, e.getMessage());
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return llmAnalysisService.analyzeChunks(job, content, filePath);
        }
    }

    private void generateAndSaveSummary(AnalysisJob job, List<AnalysisResult> results) {
        try {
            String summaryJson = llmAnalysisService.generateSummary(job, results);

            String clean = summaryJson
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            Map<?, ?> summaryMap = objectMapper.readValue(clean, Map.class);

            AnalysisSummary summary = AnalysisSummary.builder()
                    .job(job)
                    .overallScore((Integer) summaryMap.get("overallScore"))
                    .architectureSummary((String) summaryMap.get("architectureSummary"))
                    .codeQualitySummary((String) summaryMap.get("codeQualitySummary"))
                    .securitySummary((String) summaryMap.get("securitySummary"))
                    .performanceSummary((String) summaryMap.get("performanceSummary"))
                    .topIssues(objectMapper.writeValueAsString(summaryMap.get("topIssues")))
                    .techStack(objectMapper.writeValueAsString(summaryMap.get("techStack")))
                    .build();

            summaryRepository.save(summary);

        } catch (Exception e) {
            log.error("Failed to generate summary for job {}: {}", job.getId(), e.getMessage());
        }
    }
}
