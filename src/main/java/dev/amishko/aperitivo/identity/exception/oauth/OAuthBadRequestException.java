package dev.amishko.aperitivo.identity.exception.oauth;

public class OAuthBadRequestException extends RuntimeException {
    public OAuthBadRequestException(String message) {
        super(message);
    }
}
