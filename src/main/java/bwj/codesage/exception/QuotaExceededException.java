package bwj.codesage.exception;

public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException() {
        super("QUOTA_EXCEEDED");
    }
}
