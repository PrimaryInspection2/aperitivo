package dev.amishko.aperitivo.identity.util.cipher;

import dev.amishko.aperitivo.identity.exception.terminal.CryptoOperationException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class AesGcmCipher {

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final String ENCRYPTION_KEY_ALGORITHM = "AES";

    private static final int ENCRYPTION_KEY_SIZE_BYTES = 32; // 256 bits
    private static final int IV_SIZE_BYTES = 12; // 96 bits
    private static final int GCM_TAG_SIZE_BITS = 128;


    public AesGcmCipher(EncryptionProperties encryptionProperties) {
        secretKey = generateSecretKey(encryptionProperties.key());
    }

    public byte[] encrypt(byte[] plaintext) {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            byte[] ivBytes = generateRandomIV();
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_SIZE_BITS, ivBytes);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmParameterSpec);

            byte[] cipherText = cipher.doFinal(plaintext);
            return addIvToCipherText(ivBytes, cipherText);
        } catch (GeneralSecurityException ex) {
            throw new CryptoOperationException("AES-GCM encryption failed", ex);
        }
    }

    public byte[] decrypt(byte[] ciphertext) {
        Cipher cipher;
        if (ciphertext.length <= IV_SIZE_BYTES) {
            throw new IllegalArgumentException("Ciphertext is too short to contain IV and data");
        }
        try {
            cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
            byte[] ivBytes = extractIV(ciphertext);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_SIZE_BITS, ivBytes);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);

            byte[] ciphertextWithoutIv = extractCiphertext(ciphertext);
            return cipher.doFinal(ciphertextWithoutIv);
        } catch (GeneralSecurityException ex) {
            throw new CryptoOperationException("AES-GCM decryption failed", ex);
        }
    }

    private SecretKey generateSecretKey(String secretKeyBase64) {
        if (secretKeyBase64 == null || secretKeyBase64.isBlank() || secretKeyBase64.startsWith("${")) {
            throw new IllegalStateException(
                    "aperitivo.identity.encryption.key is not set — AES_GCM_SECRET is missing from .env");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secretKeyBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("aperitivo.identity.encryption.key must be Base64-encoded", ex);
        }
        if (keyBytes.length != ENCRYPTION_KEY_SIZE_BYTES) {
            throw new IllegalArgumentException("Encryption key must be 32 bytes for AES-256");
        }
        return new SecretKeySpec(keyBytes, ENCRYPTION_KEY_ALGORITHM);
    }

    private byte[] addIvToCipherText(byte[] iv, byte[] ciphertext) {
        return ByteBuffer.allocate(iv.length + ciphertext.length)
                .put(iv)
                .put(ciphertext)
                .array();
    }

    private byte[] extractIV(byte[] ciphertextWithIV) {
        return Arrays.copyOfRange(ciphertextWithIV, 0, IV_SIZE_BYTES);
    }

    private byte[] extractCiphertext(byte[] ciphertextWithIV) {
        return Arrays.copyOfRange(ciphertextWithIV, IV_SIZE_BYTES, ciphertextWithIV.length);
    }

    private byte[] generateRandomIV() {
        byte[] iv = new byte[IV_SIZE_BYTES];
        secureRandom.nextBytes(iv);
        return iv;
    }
}

