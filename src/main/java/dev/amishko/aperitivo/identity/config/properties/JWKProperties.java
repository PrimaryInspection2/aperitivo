package dev.amishko.aperitivo.identity.config.properties;

import lombok.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@ConfigurationProperties(prefix = "aperitivo.identity.jwt")
public record JWKProperties(RSAPublicKey publicKey, RSAPrivateKey privateKey, String keyId, String issuer, String audience) {

    public JWKProperties {
        if (publicKey == null) {
            throw new IllegalArgumentException("aperitivo.identity.jwt.public-key must be non-null");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("aperitivo.identity.jwt.private-key must be non-null");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("aperitivo.identity.jwt.key-id must be non-blank");
        }
        if(audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("aperitivo.identity.jwt.audience must be non-blank");
        }
        if(issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("aperitivo.identity.jwt.issuer must be non-blank");
        }
    }

    @Override
    @NonNull
    public String toString() {
        return "JWKProperties{" + "publicKey=" + publicKey + '}';
    }
}
