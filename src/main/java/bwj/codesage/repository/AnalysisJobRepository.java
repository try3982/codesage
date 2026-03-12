package bwj.codesage.repository;

import bwj.codesage.domain.entity.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {
}
