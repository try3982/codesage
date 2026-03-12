package bwj.codesage.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "analysis_jobs")
@Getter
@Setter
@NoArgsConstructor
public class AnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String repoOwner;

    @Column(nullable = false)
    private String repoName;

    private String commitHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.PENDING;

    private int totalFiles;
    private int analyzedFiles;
    private int skippedFiles;

    @Column(length = 500)
    private String currentFile;

    private String errorMessage;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public void markInProgress() {
        this.status = JobStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    public void markDone() {
        this.status = JobStatus.DONE;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }

    public int getProgressPercent() {
        if (totalFiles == 0) return 0;
        return (int) ((analyzedFiles * 100.0) / totalFiles);
    }

    public enum JobStatus {
        PENDING, IN_PROGRESS, DONE, FAILED
    }
}
