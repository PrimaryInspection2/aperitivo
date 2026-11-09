package dev.amishko.aperitivo.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aperitivo.identity.refresh-token")
public record AperitivoRefreshTokenProperties(Integer inactiveDaysForInvalidation, Integer absoluteDaysForInvalidation,
                                              Integer graceWindowSeconds) {
    public AperitivoRefreshTokenProperties {
        if (inactiveDaysForInvalidation == null) {
            throw new IllegalArgumentException("aperitivo.identity.refresh-token.inactive-days-for-invalidation must be non-null");
        }
        if (absoluteDaysForInvalidation == null) {
            throw new IllegalArgumentException("aperitivo.identity.refresh-token.absolute-days-for-invalidation must be non-null");
        }
        if (graceWindowSeconds == null) {
            throw new IllegalArgumentException("aperitivo.identity.refresh-token.grace-window-seconds must be non-blank");
        }
    }
}
