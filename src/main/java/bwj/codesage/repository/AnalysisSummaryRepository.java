package bwj.codesage.repository;

import bwj.codesage.domain.entity.AnalysisJob;
import bwj.codesage.domain.entity.AnalysisSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AnalysisSummaryRepository extends JpaRepository<AnalysisSummary, UUID> {
    Optional<AnalysisSummary> findByJob(AnalysisJob job);
}
