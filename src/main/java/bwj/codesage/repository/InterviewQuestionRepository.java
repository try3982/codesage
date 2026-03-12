package bwj.codesage.repository;

import bwj.codesage.domain.entity.AnalysisJob;
import bwj.codesage.domain.entity.InterviewQuestion;
import bwj.codesage.domain.enums.InterviewRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, UUID> {

    List<InterviewQuestion> findByJob(AnalysisJob job);

    List<InterviewQuestion> findByJobId(UUID jobId);

    List<InterviewQuestion> findByJobIdAndRole(UUID jobId, InterviewRole role);
}
