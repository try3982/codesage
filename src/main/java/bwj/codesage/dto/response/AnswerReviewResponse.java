package bwj.codesage.dto.response;

import bwj.codesage.domain.entity.AnswerReview;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public record AnswerReviewResponse(
        Integer score,
        String scoreLabel,
        List<String> improvements,
        String bestAnswer,
        String whyBest
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AnswerReviewResponse from(AnswerReview review) {
        List<String> improvements = null;
        if (review.getImprovements() != null) {
            try {
                improvements = MAPPER.readValue(review.getImprovements(), new TypeReference<>() {});
            } catch (Exception ignored) {
                improvements = List.of(review.getImprovements());
            }
        }
        return new AnswerReviewResponse(
                review.getScore(),
                review.getScoreLabel(),
                improvements,
                review.getBestAnswer(),
                review.getWhyBest()
        );
    }
}
