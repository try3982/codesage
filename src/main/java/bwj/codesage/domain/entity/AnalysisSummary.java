package bwj.codesage.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "analysis_summaries")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private AnalysisJob job;

    private Integer overallScore;

    @Column(columnDefinition = "TEXT")
    private String architectureSummary;

    @Column(columnDefinition = "TEXT")
    private String codeQualitySummary;

    @Column(columnDefinition = "TEXT")
    private String securitySummary;

    @Column(columnDefinition = "TEXT")
    private String performanceSummary;

    @Column(columnDefinition = "TEXT")
    private String topIssues;

    @Column(columnDefinition = "TEXT")
    private String techStack;
}
