package dev.amishko.aperitivo.identity.util.cipher;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aperitivo.identity.encryption")
public record EncryptionProperties(String key) {
}
