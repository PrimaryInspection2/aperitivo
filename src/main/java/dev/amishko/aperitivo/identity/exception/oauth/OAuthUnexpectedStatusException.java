package dev.amishko.aperitivo.identity.exception.oauth;

public class OAuthUnexpectedStatusException extends RuntimeException {
    public OAuthUnexpectedStatusException(String message) {
        super(message);
    }
}
