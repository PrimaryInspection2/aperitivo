package dev.amishko.aperitivo.identity.security.util;

import dev.amishko.aperitivo.identity.util.cipher.AesGcmCipher;
import dev.amishko.aperitivo.identity.util.cipher.EncryptedBytesConverter;
import dev.amishko.aperitivo.identity.util.cipher.EncryptionProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedBytesConverterTest {

    private final AesGcmCipher cipher = new AesGcmCipher(new EncryptionProperties(
            Base64.getEncoder().encodeToString(new byte[32])));

    private final EncryptedBytesConverter converter = new EncryptedBytesConverter(cipher);

    @Test
    void roundTripPreservesString() {
        String plaintext = "strava-access-token-abc123";

        String recovered = converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(plaintext));

        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void storedBytesDoNotContainPlaintext() {
        String plaintext = "sensitive-token-value";

        byte[] stored = converter.convertToDatabaseColumn(plaintext);

        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain(plaintext);
    }

    @Test
    void nullPlaintextMapsToNullColumn() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void nullColumnMapsToNullAttribute() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void nonAsciiIsPreservedViaUtf8() {
        String plaintext = "токен αβγ 🍸";

        String recovered = converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(plaintext));

        assertThat(recovered).isEqualTo(plaintext);
    }
}
