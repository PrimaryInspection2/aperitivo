package dev.amishko.aperitivo.identity.util.cipher;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Converter
@RequiredArgsConstructor
public class EncryptedBytesConverter implements AttributeConverter<String, byte[]> {

    private final AesGcmCipher cipher;


    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        return cipher.encrypt(attribute.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) return null;
        return new String(cipher.decrypt(dbData), StandardCharsets.UTF_8);
    }
}
