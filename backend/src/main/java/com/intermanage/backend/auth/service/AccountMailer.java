package com.intermanage.backend.auth.service;

import java.time.Instant;

/** Wiadomości do użytkowników o ich koncie. Wysyła je {@code EmailAccountMailer}, w testach jest podmieniany. */
public interface AccountMailer {

    void sendActivationLink(String email, String displayName, String username, String activationLink,
            Instant expiresAt);
}
