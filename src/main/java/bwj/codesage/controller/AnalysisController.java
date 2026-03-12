package bwj.codesage.controller;

import bwj.codesage.domain.entity.AnalysisJob;
import bwj.codesage.domain.entity.AnalysisResult;
import bwj.codesage.domain.entity.AnalysisSummary;
import bwj.codesage.dto.AnalysisJobResponse;
import bwj.codesage.dto.StartAnalysisRequest;
import bwj.codesage.dto.request.AnswerReviewRequest;
import bwj.codesage.dto.request.InterviewGenerateRequest;
import bwj.codesage.dto.response.AnswerReviewResponse;
import bwj.codesage.dto.response.InterviewQuestionResponse;
import bwj.codesage.exception.JobNotFoundException;
import bwj.codesage.repository.AnalysisJobRepository;
import bwj.codesage.repository.AnalysisResultRepository;
import bwj.codesage.repository.AnalysisSummaryRepository;
import bwj.codesage.service.AnalysisPipelineService;
import bwj.codesage.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisPipelineService analysisPipelineService;
    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final AnalysisSummaryRepository analysisSummaryRepository;
    private final InterviewService interviewService;

    @PostMapping
    public ResponseEntity<AnalysisJobResponse> startAnalysis(@RequestBody StartAnalysisRequest request) {
        String repoUrl = request.repoUrl();
        String[] parts = parseRepoUrl(repoUrl);
        String owner = parts[0];
        String repo = parts[1];

        AnalysisJob job = new AnalysisJob();
        job.setRepoOwner(owner);
        job.setRepoName(repo);
        AnalysisJob saved = analysisJobRepository.save(job);

        log.info("Created analysis job {} for {}/{}", saved.getId(), owner, repo);

        analysisPipelineService.runAnalysis(saved.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AnalysisJobResponse.from(saved));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<AnalysisJobResponse> getJob(@PathVariable UUID jobId) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        return ResponseEntity.ok(AnalysisJobResponse.from(job));
    }

    @GetMapping("/{jobId}/results")
    public ResponseEntity<List<AnalysisResult>> getResults(@PathVariable UUID jobId) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        return ResponseEntity.ok(analysisResultRepository.findByJob(job));
    }

    @GetMapping("/{jobId}/summary")
    public ResponseEntity<AnalysisSummary> getSummary(@PathVariable UUID jobId) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        return analysisSummaryRepository.findByJob(job)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new IllegalStateException("Analysis not completed yet for job: " + jobId));
    }

    @PostMapping("/{jobId}/interview/generate")
    public ResponseEntity<List<InterviewQuestionResponse>> generateInterview(
            @PathVariable UUID jobId,
            @RequestBody InterviewGenerateRequest request) {
        List<InterviewQuestionResponse> questions = interviewService.generateQuestions(jobId, request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(questions);
    }

    @GetMapping("/{jobId}/interview")
    public ResponseEntity<List<InterviewQuestionResponse>> getInterview(
            @PathVariable UUID jobId,
            @RequestParam(defaultValue = "junior") String role) {
        return ResponseEntity.ok(interviewService.getQuestions(jobId, role));
    }

    @PostMapping("/{jobId}/interview/{questionId}/review")
    public ResponseEntity<AnswerReviewResponse> reviewAnswer(
            @PathVariable UUID jobId,
            @PathVariable UUID questionId,
            @RequestBody AnswerReviewRequest request) {
        return ResponseEntity.ok(interviewService.reviewAnswer(questionId, request.userAnswer()));
    }

    private String[] parseRepoUrl(String repoUrl) {
        // Expects format: https://github.com/owner/repo
        String[] segments = repoUrl.replaceAll("/$", "").split("/");
        if (segments.length < 2) {
            throw new IllegalArgumentException("Invalid repository URL: " + repoUrl);
        }
        String repo = segments[segments.length - 1];
        String owner = segments[segments.length - 2];
        return new String[]{owner, repo};
    }
}
