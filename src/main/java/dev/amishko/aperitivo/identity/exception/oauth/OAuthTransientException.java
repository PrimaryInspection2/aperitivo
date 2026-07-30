package dev.amishko.aperitivo.identity.exception.oauth;

import dev.amishko.aperitivo.identity.exception.retryable.TransientException;

public class OAuthTransientException extends TransientException {
    public OAuthTransientException(String message) {
        super(message);
    }
}
