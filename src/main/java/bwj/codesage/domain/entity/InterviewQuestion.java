package bwj.codesage.domain.entity;

import bwj.codesage.domain.enums.InterviewRole;
import bwj.codesage.domain.enums.QuestionType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "interview_questions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private AnalysisJob job;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private InterviewRole role;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private QuestionType type;

    /** 난이도 1~5 (1: 쉬움, 5: 어려움) */
    @Column(nullable = false)
    private int difficulty;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(length = 200)
    private String focus;

    @Column(columnDefinition = "TEXT")
    private String modelAnswer;

    @Column(columnDefinition = "TEXT")
    private String whyBest;

    // 사용자 답변 & 리뷰 (답변 제출 후 채워짐)
    @Column(columnDefinition = "TEXT")
    private String userAnswer;

    @Column(columnDefinition = "TEXT")
    private String answerReview;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
