package com.intermanage.backend.component;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.intermanage.backend.service.AccountMailer;

/** Zamiast wysyłać maila wypisuje jego treść do logów backendu. Do podmiany, gdy dojdzie system maili. */
@Component
public class LoggingAccountMailer implements AccountMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingAccountMailer.class);

    @Override
    public void sendActivationLink(String email, String displayName, String username, String activationLink,
            Instant expiresAt) {
        log.info("[MAIL] Do: {} <{}> | Aktywacja konta '{}' w Intermanage: {} (link ważny do {})",
                displayName, email, username, activationLink, expiresAt);
    }
}
