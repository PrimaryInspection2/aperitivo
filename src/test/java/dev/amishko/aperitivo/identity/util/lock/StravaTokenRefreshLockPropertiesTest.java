package dev.amishko.aperitivo.identity.util.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StravaTokenRefreshLockPropertiesTest {

    @Test
    @DisplayName("accepts positive wait and lease")
    void acceptsPositiveValues() {
        assertThatCode(() -> new StravaTokenRefreshLockProperties(Duration.ofSeconds(5), Duration.ofSeconds(15)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects null wait")
    void rejectsNullWait() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StravaTokenRefreshLockProperties(null, Duration.ofSeconds(15)))
                .withMessageContaining("wait-time");
    }

    @Test
    @DisplayName("rejects zero wait")
    void rejectsZeroWait() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StravaTokenRefreshLockProperties(Duration.ZERO, Duration.ofSeconds(15)))
                .withMessageContaining("wait-time");
    }

    @Test
    @DisplayName("rejects negative wait")
    void rejectsNegativeWait() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StravaTokenRefreshLockProperties(Duration.ofSeconds(-1), Duration.ofSeconds(15)))
                .withMessageContaining("wait-time");
    }

    @Test
    @DisplayName("rejects null lease")
    void rejectsNullLease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StravaTokenRefreshLockProperties(Duration.ofSeconds(5), null))
                .withMessageContaining("lease-time");
    }

    @Test
    @DisplayName("rejects zero lease")
    void rejectsZeroLease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StravaTokenRefreshLockProperties(Duration.ofSeconds(5), Duration.ZERO))
                .withMessageContaining("lease-time");
    }

    @Test
    @DisplayName("rejects negative lease")
    void rejectsNegativeLease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StravaTokenRefreshLockProperties(Duration.ofSeconds(5), Duration.ofSeconds(-1)))
                .withMessageContaining("lease-time");
    }
}
