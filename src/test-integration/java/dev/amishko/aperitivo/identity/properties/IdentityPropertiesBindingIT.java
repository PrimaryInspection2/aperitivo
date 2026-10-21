package dev.amishko.aperitivo.identity.properties;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.config.properties.AuditExecutorProperties;
import dev.amishko.aperitivo.identity.util.cipher.EncryptionProperties;
import dev.amishko.aperitivo.identity.util.lock.TokenRefreshLockProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@code @ConfigurationProperties} bindings for the identity BC
 * against silent property-key drift.
 *
 * <p>A typo in {@code application.yaml} (e.g. renaming
 * {@code token-refresh-lock.wait-time} → {@code token-refresh-lock.waitTime})
 * would leave the property unresolved and the record's non-nullable constructor
 * validation would fail with an obscure BeanCreationException. These tests
 * instead assert on the concrete resolved bean, so any drift shows up as a
 * pointed failure at the exact property.
 *
 * <p>Two contexts are covered here:
 * <ol>
 *   <li>values from {@code application.yaml} defaults ({@code TokenRefreshLockProperties},
 *       {@code AuditExecutorProperties}), which flow through unchanged into the IT
 *       context — the itest profile only overrides {@code encryption.key};</li>
 *   <li>the value from {@code application-itest.yaml} for
 *       {@code EncryptionProperties.key}, which proves per-profile override wiring
 *       actually reaches the target bean.</li>
 * </ol>
 */
class IdentityPropertiesBindingIT extends BaseIT {

    @Autowired
    private TokenRefreshLockProperties tokenRefreshLockProperties;

    @Autowired
    private AuditExecutorProperties auditExecutorProperties;

    @Autowired
    private EncryptionProperties encryptionProperties;

    @Nested
    @DisplayName("aperitivo.identity.token-refresh-lock.*")
    class LockProps {

        @Test
        @DisplayName("wait-time and lease-time bind from application.yaml defaults")
        void bindsFromDefaults() {
            // Matches application.yaml: wait-time=5s, lease-time=15s.
            assertThat(tokenRefreshLockProperties.waitTime()).isEqualTo(Duration.ofSeconds(5));
            assertThat(tokenRefreshLockProperties.leaseTime()).isEqualTo(Duration.ofSeconds(15));
        }
    }

    @Nested
    @DisplayName("aperitivo.identity.audit-executor.*")
    class AuditProps {

        @Test
        @DisplayName("core/max/queue/prefix bind from application.yaml defaults")
        void bindsFromDefaults() {
            // Matches application.yaml: core=2, max=4, queue=500, prefix=iam-audit-.
            assertThat(auditExecutorProperties.corePoolSize()).isEqualTo(2);
            assertThat(auditExecutorProperties.maxPoolSize()).isEqualTo(4);
            assertThat(auditExecutorProperties.queueCapacity()).isEqualTo(500);
            assertThat(auditExecutorProperties.threadNamePrefix()).isEqualTo("iam-audit-");
        }
    }

    @Nested
    @DisplayName("aperitivo.identity.encryption.*")
    class EncryptionProps {

        @Test
        @DisplayName("key binds from application-itest.yaml (proves profile override reaches the bean)")
        void bindsFromItestOverride() {
            // Matches application-itest.yaml: 32 zero-bytes in Base64.
            String expected = Base64.getEncoder().encodeToString(new byte[32]);
            assertThat(encryptionProperties.key()).isEqualTo(expected);
            assertThat(Base64.getDecoder().decode(encryptionProperties.key()))
                    .as("decoded key must be exactly 32 bytes (AES-256)")
                    .hasSize(32);
        }
    }
}
