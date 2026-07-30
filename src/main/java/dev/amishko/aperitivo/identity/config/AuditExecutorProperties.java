package dev.amishko.aperitivo.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aperitivo.identity.audit-executor")
public record AuditExecutorProperties(int corePoolSize,
                                       int maxPoolSize,
                                       int queueCapacity,
                                       String threadNamePrefix) {

    public AuditExecutorProperties {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.audit-executor.core-pool-size must be >= 1");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.audit-executor.max-pool-size must be >= core-pool-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.audit-executor.queue-capacity must be >= 0");
        }
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.audit-executor.thread-name-prefix must be non-blank");
        }
    }
}
