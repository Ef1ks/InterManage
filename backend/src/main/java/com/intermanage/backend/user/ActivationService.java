package com.intermanage.backend.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.ApiException;
import com.intermanage.backend.mail.AccountMailer;

/** Linki aktywacyjne dla zaproszonych pracowników: jednorazowe, ważne {@code app.auth.activation-ttl}. */
@Service
public class ActivationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ActivationTokenRepository tokens;
    private final AccountMailer mailer;
    private final String frontendUrl;
    private final Duration ttl;

    public ActivationService(ActivationTokenRepository tokens, AccountMailer mailer,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.auth.activation-ttl}") Duration ttl) {
        this.tokens = tokens;
        this.mailer = mailer;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.ttl = ttl;
    }

    /** Unieważnia poprzednie linki użytkownika i wysyła nowy. */
    @Transactional
    public void sendActivationLink(AppUser user) {
        tokens.deleteByUserId(user.getId());

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ActivationToken saved = tokens.save(new ActivationToken(user, hash(token), Instant.now().plus(ttl)));

        mailer.sendActivationLink(user.getEmail(), user.getDisplayName(), user.getUsername(),
                frontendUrl + "/aktywacja?token=" + token, saved.getExpiresAt());
    }

    /** Zużywa link i zwraca użytkownika do zalogowania. Po aktywacji musi on ustawić hasło. */
    @Transactional
    public AppUser activate(String token) {
        Instant now = Instant.now();
        ActivationToken activation = tokens.findByTokenHash(hash(token))
                .filter(t -> t.isUsable(now))
                .filter(t -> t.getUser().isEnabled() && t.getUser().isPendingActivation())
                .orElseThrow(() -> ApiException.badRequest(
                        "Link aktywacyjny jest nieprawidłowy, wygasł albo został już użyty. "
                                + "Poproś właściciela o wysłanie nowego."));
        activation.markUsed(now);
        return activation.getUser();
    }

    @Transactional
    public void revokeLinks(AppUser user) {
        tokens.deleteByUserId(user.getId());
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
