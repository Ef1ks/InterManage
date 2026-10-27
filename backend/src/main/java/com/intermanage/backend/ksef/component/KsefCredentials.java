package com.intermanage.backend.component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.stereotype.Component;

import com.intermanage.backend.exception.ApiException;

/**
 * Certyfikat KSeF (.crt, PEM albo DER) i jego klucz prywatny (.key, PKCS#8 PEM zaszyfrowany hasłem - tak go wydaje
 * KSeF: PBES2, AES-256). Sprawdza, czy hasło pasuje i czy klucz należy do certyfikatu.
 */
@Component
public class KsefCredentials {

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    public record Loaded(X509Certificate certificate, PrivateKey privateKey) {
    }

    public X509Certificate certificate(byte[] content) {
        try {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(content));
            if (certificate == null) {
                throw invalidCertificate();
            }
            return certificate;
        } catch (CertificateException | ClassCastException e) {
            throw invalidCertificate();
        }
    }

    /** Certyfikat i odszyfrowany klucz; błędy jako komunikaty dla użytkownika. */
    public Loaded load(String certificatePem, String privateKeyPem, String password) {
        X509Certificate certificate = certificate(certificatePem.getBytes(StandardCharsets.US_ASCII));
        PrivateKey key = privateKey(privateKeyPem, password, certificate.getPublicKey().getAlgorithm());
        if (!belongsTo(key, certificate)) {
            throw ApiException.badRequest("Klucz prywatny nie pasuje do certyfikatu - wgraj parę z tego samego wniosku")
                    .withProperty("errors", Map.of("privateKey", "Klucz nie pasuje do certyfikatu"));
        }
        return new Loaded(certificate, key);
    }

    /** Certyfikat jako PEM do zapisania (wgrany mógł być w DER). */
    public String toPem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateException e) {
            throw invalidCertificate();
        }
    }

    private PrivateKey privateKey(String pem, String password, String algorithm) {
        Matcher matcher = PEM.matcher(pem);
        if (!matcher.find()) {
            throw invalidKey("To nie jest plik klucza prywatnego (.key)");
        }
        String type = matcher.group(1);
        byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
        try {
            PKCS8EncodedKeySpec spec;
            if (type.equals("ENCRYPTED PRIVATE KEY")) {
                spec = decrypt(der, password);
            } else if (type.equals("PRIVATE KEY")) {
                spec = new PKCS8EncodedKeySpec(der);
            } else {
                throw invalidKey("Nieobsługiwany format klucza (" + type + ") - potrzebny PKCS#8 z KSeF");
            }
            return KeyFactory.getInstance(algorithm).generatePrivate(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw invalidKey("Nie udało się odczytać klucza - sprawdź plik .key");
        }
    }

    private PKCS8EncodedKeySpec decrypt(byte[] der, String password) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            throw ApiException.badRequest("Podaj hasło do klucza")
                    .withProperty("errors", Map.of("password", "Podaj hasło do klucza"));
        }
        EncryptedPrivateKeyInfo info;
        try {
            info = new EncryptedPrivateKeyInfo(der);
        } catch (IOException e) {
            throw invalidKey("Nie udało się odczytać klucza - sprawdź plik .key");
        }
        AlgorithmParameters parameters = info.getAlgParameters();
        // PBES2 opisuje się np. jako "PBEWithHmacSHA256AndAES_256" - pod tą nazwą Java ma szyfr i fabrykę klucza.
        String algorithm = parameters.toString();
        try {
            Cipher cipher = Cipher.getInstance(algorithm);
            cipher.init(Cipher.DECRYPT_MODE,
                    SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password.toCharArray())),
                    parameters);
            return info.getKeySpec(cipher);
        } catch (InvalidKeySpecException e) {
            throw ApiException.badRequest("Złe hasło do klucza")
                    .withProperty("errors", Map.of("password", "Złe hasło do klucza"));
        }
    }

    private static boolean belongsTo(PrivateKey key, X509Certificate certificate) {
        try {
            String algorithm = key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
            byte[] probe = new byte[32];
            new SecureRandom().nextBytes(probe);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static ApiException invalidCertificate() {
        return ApiException.badRequest("To nie jest plik certyfikatu (.crt)")
                .withProperty("errors", Map.of("certificate", "To nie jest plik certyfikatu"));
    }

    private static ApiException invalidKey(String message) {
        return ApiException.badRequest(message).withProperty("errors", Map.of("privateKey", message));
    }
}
