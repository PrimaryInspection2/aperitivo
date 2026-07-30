package dev.amishko.aperitivo.identity.exception.terminal;

/**
 * Exception to let user or upstream know that the integration is not active and cannot be used.
 */
public class IntegrationNotActiveException extends RuntimeException {
    public IntegrationNotActiveException(String message) {
        super(message);
    }
}
