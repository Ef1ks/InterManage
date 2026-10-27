package com.intermanage.backend.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Wystawia i weryfikuje JWT (HS256) trzymany w ciasteczku HttpOnly {@value #COOKIE_NAME}.
 * Token niesie id użytkownika ({@code sub}) i wersję sesji ({@value #SESSION_VERSION_CLAIM});
 * zmiana hasła podbija wersję w bazie, więc wcześniej wydane tokeny przestają działać.
 */
@Component
public class JwtService {

    public static final String COOKIE_NAME = "AUTH_TOKEN";
    public static final String SESSION_VERSION_CLAIM = "sv";

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration tokenTtl;
    private final boolean secureCookie;

    public JwtService(@Value("${app.auth.jwt-secret:}") String secret,
            @Value("${app.auth.token-ttl}") Duration tokenTtl,
            @Value("${app.auth.cookie-secure}") boolean secureCookie) {
        SecretKey key = secretKey(secret);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.tokenTtl = tokenTtl;
        this.secureCookie = secureCookie;
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public void writeTokenCookie(HttpServletResponse response, AppUserPrincipal principal) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(principal.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(tokenTtl))
                .claim(SESSION_VERSION_CLAIM, principal.getSessionVersion())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, tokenTtl).toString());
    }

    public void clearTokenCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static SecretKey secretKey(String secret) {
        byte[] bytes;
        if (secret == null || secret.isBlank()) {
            bytes = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(bytes);
            log.warn("Brak JWT_SECRET - klucz wylosowany przy starcie, restart backendu wyloguje wszystkich użytkowników");
        } else {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("JWT_SECRET musi mieć co najmniej " + MIN_SECRET_BYTES + " bajty");
            }
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
