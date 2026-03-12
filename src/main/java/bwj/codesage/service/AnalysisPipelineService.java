package bwj.codesage.service;

import bwj.codesage.client.GitHubClient;
import bwj.codesage.exception.QuotaExceededException;
import bwj.codesage.domain.entity.AnalysisJob;
import bwj.codesage.domain.entity.AnalysisResult;
import bwj.codesage.domain.entity.AnalysisSummary;
import bwj.codesage.repository.AnalysisJobRepository;
import bwj.codesage.repository.AnalysisResultRepository;
import bwj.codesage.repository.AnalysisSummaryRepository;
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisPipelineService {

    private final GitHubClient gitHubClient;
    private final CodeChunkingService chunkingService;
    private final LlmAnalysisService llmAnalysisService;

    private final AnalysisJobRepository jobRepository;
    private final AnalysisResultRepository resultRepository;
    private final AnalysisSummaryRepository summaryRepository;
    private final JobProgressService jobProgressService;

    private final ObjectMapper objectMapper;

    @Value("${analysis.max-files:100}")
    private int maxFiles;

    @Value("${analysis.max-file-size-kb:100}")
    private int maxFileSizeKb;

    @Value("${gemini.rate-limit-delay-ms:500}")
    private long rateLimitDelayMs;

    private static final int BATCH_SIZE = 5;

    @Async
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

            List<String> targetFiles = allFiles.stream()
                    .filter(chunkingService::isSupportedFile)
                    .sorted(Comparator.comparingInt(chunkingService::getFilePriority))
                    .limit(maxFiles)
                    .toList();

            int skipped = (int) allFiles.stream().filter(chunkingService::isSupportedFile).count() - targetFiles.size();
            job.setTotalFiles(targetFiles.size());
            job.setSkippedFiles(skipped);
            jobRepository.save(job);

            log.info("Job {} - fetching {} file contents", jobId, targetFiles.size());

            // Step 2: Fetch all file contents (no rate limit needed — GitHub API is fast)
            List<String[]> fileContents = new ArrayList<>();
            for (String filePath : targetFiles) {
                gitHubClient.getFileContent(job.getRepoOwner(), job.getRepoName(), job.getCommitHash(), filePath)
                        .filter(c -> c.length() <= maxFileSizeKb * 1024)
                        .ifPresent(c -> fileContents.add(new String[]{filePath, c}));
            }

            log.info("Job {} - analyzing {} files in batches of {}", jobId, fileContents.size(), BATCH_SIZE);

            // Step 3: Batch analyze — BATCH_SIZE files per Gemini call
            List<AnalysisResult> allResults = new ArrayList<>();
            int consecutiveRateLimitFailures = 0;

            for (int i = 0; i < fileContents.size(); i += BATCH_SIZE) {
                List<String[]> batch = fileContents.subList(i, Math.min(i + BATCH_SIZE, fileContents.size()));
                int batchNum = (i / BATCH_SIZE) + 1;
                int totalBatches = (int) Math.ceil(fileContents.size() / (double) BATCH_SIZE);

                jobProgressService.updateProgress(job.getId(), i, batch.get(0)[0]);

                try {
                    List<AnalysisResult> batchResults = llmAnalysisService.batchAnalyze(job, batch);
                    allResults.addAll(batchResults);
                    consecutiveRateLimitFailures = 0;
                    log.info("Job {} - batch {}/{} done, {} issues found so far",
                            jobId, batchNum, totalBatches, allResults.size());
                } catch (Exception e) {
                    log.warn("Batch {}/{} failed: {}", batchNum, totalBatches, e.getMessage());
                    if (e.getMessage() != null && e.getMessage().contains("429")) {
                        consecutiveRateLimitFailures++;
                        if (consecutiveRateLimitFailures >= 1) {
                            throw new QuotaExceededException();
                        }
                    }
                }

                jobProgressService.updateProgress(job.getId(), Math.min(i + BATCH_SIZE, fileContents.size()), null);

                if (i + BATCH_SIZE < fileContents.size()) {
                    Thread.sleep(rateLimitDelayMs);
                }
            }

            // Step 4: Save results + summary
            resultRepository.saveAll(allResults);
            generateAndSaveSummary(job, allResults);

            // Reload to preserve analyzedFiles updated by jobProgressService
            job = jobRepository.findById(jobId).orElse(job);
            job.markDone();
            jobRepository.save(job);

            log.info("Job {} completed — {} issues found in {} batches",
                    jobId, allResults.size(), (int) Math.ceil(fileContents.size() / (double) BATCH_SIZE));

        } catch (QuotaExceededException e) {
            log.warn("Job {} failed: Gemini API quota exceeded", jobId);
            job.markFailed("QUOTA_EXCEEDED");
            jobRepository.save(job);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.markFailed("Interrupted");
            jobRepository.save(job);
        } catch (Exception e) {
            log.error("Analysis pipeline failed for job {}: {}", jobId, e.getMessage(), e);
            job.markFailed(e.getMessage());
            jobRepository.save(job);
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
