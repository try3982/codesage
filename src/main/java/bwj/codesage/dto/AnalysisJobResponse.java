package bwj.codesage.dto;

import bwj.codesage.domain.entity.AnalysisJob;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnalysisJobResponse(
        UUID id,
        String repoOwner,
        String repoName,
        String status,
        int totalFiles,
        int analyzedFiles,
        int skippedFiles,
        int progressPercent,
        String currentFile,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMessage
) {
    public static AnalysisJobResponse from(AnalysisJob job) {
        return new AnalysisJobResponse(
                job.getId(),
                job.getRepoOwner(),
                job.getRepoName(),
                job.getStatus().name(),
                job.getTotalFiles(),
                job.getAnalyzedFiles(),
                job.getSkippedFiles(),
                job.getProgressPercent(),
                job.getCurrentFile(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getErrorMessage()
        );
    }
}
