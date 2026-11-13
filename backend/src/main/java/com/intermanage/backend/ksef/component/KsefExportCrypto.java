package com.intermanage.backend.ksef.component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Szyfrowanie paczki eksportu faktur KSeF: na każdy eksport losowy klucz AES-256 i wektor IV (16 bajtów); klucz idzie
 * do KSeF zaszyfrowany kluczem publicznym MF (RSA-OAEP, SHA-256, MGF1 z SHA-256), a KSeF szyfruje nim każdą część
 * paczki osobno (AES-256-CBC, PKCS#7). Klucz żyje tylko w pamięci na czas jednego eksportu.
 */
public final class KsefExportCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private KsefExportCrypto() {
    }

    /** Klucz paczki i to, co idzie do KSeF: klucz zaszyfrowany kluczem MF i IV, oba w Base64. */
    public record PackageKey(SecretKey key, byte[] iv, String encryptedKey, String initializationVector) {
    }

    /** Nowy klucz paczki zaszyfrowany kluczem publicznym MF (certyfikat X.509 albo sam klucz, DER w Base64). */
    public static PackageKey newKey(String mfPublicKeyBase64) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256, RANDOM);
            SecretKey key = generator.generateKey();
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey(mfPublicKeyBase64), new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            Base64.Encoder base64 = Base64.getEncoder();
            return new PackageKey(key, iv, base64.encodeToString(rsa.doFinal(key.getEncoded())),
                    base64.encodeToString(iv));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nie udało się przygotować klucza paczki faktur", e);
        }
    }

    /**
     * Odszyfrowana część paczki dopisana do {@code out}; z {@code encryptedHash} (SHA-256 w Base64 ze statusu)
     * sprawdza najpierw, czy część przyszła cała.
     */
    public static void decryptPart(byte[] encrypted, String encryptedHash, PackageKey key, OutputStream out)
            throws IOException {
        try {
            if (encryptedHash != null) {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(encrypted);
                if (!MessageDigest.isEqual(hash, Base64.getDecoder().decode(encryptedHash))) {
                    throw new IOException("Część paczki faktur przyszła uszkodzona (inny skrót SHA-256)");
                }
            }
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, key.key(), new IvParameterSpec(key.iv()));
            out.write(aes.doFinal(encrypted));
        } catch (GeneralSecurityException e) {
            throw new IOException("Nie udało się odszyfrować części paczki faktur: " + e.getMessage(), e);
        }
    }

    private static PublicKey publicKey(String base64) throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der))
                    .getPublicKey();
        } catch (GeneralSecurityException e) {
            // Niektóre wpisy KSeF to sam klucz (SubjectPublicKeyInfo), nie certyfikat.
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        }
    }
}
