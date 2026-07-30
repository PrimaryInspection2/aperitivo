package dev.amishko.aperitivo.identity.exception.terminal;

public class CryptoOperationException extends RuntimeException {

    public CryptoOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
