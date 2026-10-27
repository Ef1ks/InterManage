package com.intermanage.backend.component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.intermanage.backend.exception.ApiException;

/**
 * Szyfruje sekrety zapisywane w bazie (hasło do klucza KSeF) kluczem aplikacji {@code APP_SECRET_KEY}: 32 bajty
 * w Base64, AES-256-GCM. Klucz musi być stały - inny po restarcie oznacza, że zapisanych sekretów nie da się odczytać.
 */
@Component
public class SecretCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.secret-key:}") String secretKey) {
        this.key = parse(secretKey);
    }

    private static SecretKey parse(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secretKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_SECRET_KEY musi być w Base64", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("APP_SECRET_KEY musi mieć 32 bajty (44 znaki w Base64)");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nie udało się zaszyfrować sekretu", e);
        }
    }

    public String decrypt(String encrypted) {
        byte[] bytes = Base64.getDecoder().decode(encrypted);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw ApiException.conflict("Nie da się odczytać zapisanego hasła - klucz aplikacji (APP_SECRET_KEY) "
                    + "zmienił się od zapisu. Wgraj certyfikat i hasło ponownie.");
        }
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw ApiException.conflict("Serwer nie ma klucza do szyfrowania haseł (APP_SECRET_KEY) - "
                    + "ustaw go, zanim zapiszesz połączenie z KSeF.");
        }
        return key;
    }
}
