package dev.amishko.aperitivo.identity.security.util;

import dev.amishko.aperitivo.identity.util.cipher.AesGcmCipher;
import dev.amishko.aperitivo.identity.util.cipher.EncryptionProperties;
import dev.amishko.aperitivo.identity.exception.terminal.CryptoOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCipherTest {

    private static final String VALID_KEY_BASE64 =
            Base64.getEncoder().encodeToString(new byte[32]);

    private final AesGcmCipher cipher = new AesGcmCipher(new EncryptionProperties(VALID_KEY_BASE64));

    @Nested
    @DisplayName("construction / key validation")
    class Construction {

        @Test
        void rejectsNonBase64Key() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AesGcmCipher(new EncryptionProperties("not base64 !!!")))
                    .withMessageContaining("Base64");
        }

        @Test
        void rejectsWrongKeyLength() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AesGcmCipher(new EncryptionProperties(tooShort)))
                    .withMessageContaining("32 bytes");
        }

        @Test
        void acceptsValid32ByteKey() {
            assertThatCode(() -> new AesGcmCipher(new EncryptionProperties(VALID_KEY_BASE64)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("encrypt / decrypt round-trip")
    class RoundTrip {

        @Test
        void plaintextSurvivesRoundTrip() {
            byte[] plaintext = "hello aperitivo".getBytes(StandardCharsets.UTF_8);

            byte[] recovered = cipher.decrypt(cipher.encrypt(plaintext));

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        void handlesEmptyPlaintext() {
            byte[] stored = cipher.encrypt(new byte[0]);
            byte[] recovered = cipher.decrypt(stored);

            assertThat(recovered).isEmpty();
            assertThat(stored).hasSize(12 + 0 + 16);
        }

        @Test
        void handlesLongPlaintext() {
            byte[] plaintext = new byte[4096];
            for (int i = 0; i < plaintext.length; i++) plaintext[i] = (byte) i;

            byte[] recovered = cipher.decrypt(cipher.encrypt(plaintext));

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        void handlesNonAsciiPlaintext() {
            byte[] plaintext = "токен αβγ 🍸".getBytes(StandardCharsets.UTF_8);

            byte[] recovered = cipher.decrypt(cipher.encrypt(plaintext));

            assertThat(recovered).isEqualTo(plaintext);
        }
    }

    @Nested
    @DisplayName("output-format contract")
    class OutputFormat {

        @Test
        void outputSizeIsNoncePlusPlaintextPlusTag() {
            byte[] plaintext = "abc".getBytes(StandardCharsets.UTF_8);

            byte[] stored = cipher.encrypt(plaintext);

            assertThat(stored).hasSize(12 + plaintext.length + 16);
        }

        @Test
        void twoEncryptionsProduceDifferentCiphertext() {
            byte[] plaintext = "same input".getBytes(StandardCharsets.UTF_8);

            byte[] first = cipher.encrypt(plaintext);
            byte[] second = cipher.encrypt(plaintext);

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        void noncesAreUniqueOverManyEncryptions() {
            int samples = 5_000;
            Set<String> nonces = new HashSet<>();
            for (int i = 0; i < samples; i++) {
                byte[] stored = cipher.encrypt("payload".getBytes(StandardCharsets.UTF_8));
                byte[] nonce = new byte[12];
                System.arraycopy(stored, 0, nonce, 0, 12);
                nonces.add(Base64.getEncoder().encodeToString(nonce));
            }
            assertThat(nonces).hasSize(samples);
        }
    }

    @Nested
    @DisplayName("integrity — AEAD authentication")
    class Integrity {

        @Test
        void tamperedCiphertextIsRejected() {
            byte[] stored = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8));
            stored[stored.length / 2] ^= (byte) 0x01;

            assertThatThrownBy(() -> cipher.decrypt(stored))
                    .isInstanceOf(CryptoOperationException.class)
                    .hasCauseInstanceOf(javax.crypto.AEADBadTagException.class);
        }

        @Test
        void tamperedNonceIsRejected() {
            byte[] stored = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8));
            stored[0] ^= (byte) 0xFF;

            assertThatThrownBy(() -> cipher.decrypt(stored))
                    .isInstanceOf(CryptoOperationException.class);
        }

        @Test
        void tamperedTagIsRejected() {
            byte[] stored = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8));
            stored[stored.length - 1] ^= (byte) 0x01;

            assertThatThrownBy(() -> cipher.decrypt(stored))
                    .isInstanceOf(CryptoOperationException.class);
        }

        @Test
        void ciphertextEncryptedUnderDifferentKeyIsRejected() {
            byte[] otherKey = new byte[32];
            otherKey[0] = 1;
            AesGcmCipher other = new AesGcmCipher(new EncryptionProperties(Base64.getEncoder().encodeToString(otherKey)));
            byte[] fromOther = other.encrypt("secret".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> cipher.decrypt(fromOther))
                    .isInstanceOf(CryptoOperationException.class);
        }

        @Test
        void malformedPayloadShorterThanNonceIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cipher.decrypt(new byte[8]))
                    .withMessageContaining("too short");
        }
    }
}
