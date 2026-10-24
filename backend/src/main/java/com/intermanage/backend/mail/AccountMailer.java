package com.intermanage.backend.mail;

import java.time.Instant;

/** Wiadomości do użytkowników o ich koncie. Docelowo wysyłka maili; na razie {@link LoggingAccountMailer}. */
public interface AccountMailer {

    void sendActivationLink(String email, String displayName, String username, String activationLink,
            Instant expiresAt);
}
