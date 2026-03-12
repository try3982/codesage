package bwj.codesage.repository;

import bwj.codesage.domain.entity.AnalysisJob;
import bwj.codesage.domain.entity.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, UUID> {
    List<AnalysisResult> findByJob(AnalysisJob job);
}
