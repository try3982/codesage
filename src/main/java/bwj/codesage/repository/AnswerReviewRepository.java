package bwj.codesage.repository;

import bwj.codesage.domain.entity.AnswerReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AnswerReviewRepository extends JpaRepository<AnswerReview, UUID> {

    Optional<AnswerReview> findTopByQuestionIdOrderByCreatedAtDesc(UUID questionId);
}
