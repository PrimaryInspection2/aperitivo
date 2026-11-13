package dev.amishko.aperitivo.identity.service.aperitivo;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "aperitivo.identity.refresh-token")
public record AperitivoRefreshTokenProperties(Integer inactiveDaysForInvalidation,
                                              Integer absoluteDaysForInvalidation,
                                              Integer graceWindowSeconds) {

    public AperitivoRefreshTokenProperties {
        if (inactiveDaysForInvalidation == null || inactiveDaysForInvalidation <= 0) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.refresh-token.inactive-days-for-invalidation must be > 0");
        }
        if (absoluteDaysForInvalidation == null || absoluteDaysForInvalidation < inactiveDaysForInvalidation) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.refresh-token.absolute-days-for-invalidation must be >= inactive-days-for-invalidation");
        }
        if (graceWindowSeconds == null || graceWindowSeconds < 0) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.refresh-token.grace-window-seconds must be >= 0");
        }
    }
}
