package dev.amishko.aperitivo.identity.util.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TokenRefreshLockPropertiesTest {

    @Test
    @DisplayName("accepts positive wait and lease")
    void acceptsPositiveValues() {
        assertThatCode(() -> new TokenRefreshLockProperties(Duration.ofSeconds(5), Duration.ofSeconds(15)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects null wait")
    void rejectsNullWait() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenRefreshLockProperties(null, Duration.ofSeconds(15)))
                .withMessageContaining("wait-time");
    }

    @Test
    @DisplayName("rejects zero wait")
    void rejectsZeroWait() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenRefreshLockProperties(Duration.ZERO, Duration.ofSeconds(15)))
                .withMessageContaining("wait-time");
    }

    @Test
    @DisplayName("rejects negative wait")
    void rejectsNegativeWait() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenRefreshLockProperties(Duration.ofSeconds(-1), Duration.ofSeconds(15)))
                .withMessageContaining("wait-time");
    }

    @Test
    @DisplayName("rejects null lease")
    void rejectsNullLease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenRefreshLockProperties(Duration.ofSeconds(5), null))
                .withMessageContaining("lease-time");
    }

    @Test
    @DisplayName("rejects zero lease")
    void rejectsZeroLease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenRefreshLockProperties(Duration.ofSeconds(5), Duration.ZERO))
                .withMessageContaining("lease-time");
    }

    @Test
    @DisplayName("rejects negative lease")
    void rejectsNegativeLease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenRefreshLockProperties(Duration.ofSeconds(5), Duration.ofSeconds(-1)))
                .withMessageContaining("lease-time");
    }
}
