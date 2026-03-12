package bwj.codesage.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "answer_reviews")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private InterviewQuestion question;

    @Column(columnDefinition = "TEXT")
    private String userAnswer;

    private Integer score;

    @Column(length = 200)
    private String scoreLabel;

    /** JSON 배열 형태로 저장 (e.g. ["개선점1", "개선점2"]) */
    @Column(columnDefinition = "TEXT")
    private String improvements;

    @Column(columnDefinition = "TEXT")
    private String bestAnswer;

    @Column(columnDefinition = "TEXT")
    private String whyBest;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
