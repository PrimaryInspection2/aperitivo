package dev.amishko.aperitivo.identity.exception.terminal;

public class ConnectedSourceNotFoundException extends RuntimeException {
    public ConnectedSourceNotFoundException(String message) {
        super(message);
    }
}
