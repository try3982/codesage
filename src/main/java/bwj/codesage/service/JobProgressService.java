package bwj.codesage.service;

import bwj.codesage.repository.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobProgressService {

    private final AnalysisJobRepository jobRepository;

    /**
     * 파일별 진행률을 독립 트랜잭션으로 즉시 커밋합니다.
     * REQUIRES_NEW를 사용하여 외부 트랜잭션과 분리, 다른 세션에서 즉시 조회 가능합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID jobId, int analyzedFiles, String currentFile) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setAnalyzedFiles(analyzedFiles);
            job.setCurrentFile(currentFile);
            jobRepository.save(job);
            log.debug("Progress updated: job={} {}/{} currentFile={}",
                    jobId, analyzedFiles, job.getTotalFiles(), currentFile);
        });
    }
}
