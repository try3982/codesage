package bwj.codesage.exception;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(UUID jobId) {
        super("Job not found: " + jobId);
    }

    public JobNotFoundException(String message) {
        super(message);
    }
}
