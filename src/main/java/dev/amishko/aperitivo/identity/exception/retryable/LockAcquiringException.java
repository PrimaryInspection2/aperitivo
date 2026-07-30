package dev.amishko.aperitivo.identity.exception.retryable;

public class LockAcquiringException extends TransientException {

    public LockAcquiringException(String message) {
        super(message);
    }

    public LockAcquiringException(String message, Throwable cause) {
        super(message, cause);
    }
}
