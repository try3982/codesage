package bwj.codesage.dto.response;

import bwj.codesage.domain.entity.InterviewQuestion;
import bwj.codesage.domain.enums.InterviewRole;
import bwj.codesage.domain.enums.QuestionType;

import java.util.UUID;

public record InterviewQuestionResponse(
        UUID id,
        InterviewRole role,
        QuestionType type,
        int difficulty,
        String question,
        String focus,
        String modelAnswer,
        String whyBest
) {
    public static InterviewQuestionResponse from(InterviewQuestion q) {
        return new InterviewQuestionResponse(
                q.getId(),
                q.getRole(),
                q.getType(),
                q.getDifficulty(),
                q.getQuestion(),
                q.getFocus(),
                q.getModelAnswer(),
                q.getWhyBest()
        );
    }
}
