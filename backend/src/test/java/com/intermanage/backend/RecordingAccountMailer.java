package com.intermanage.backend;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.intermanage.backend.mail.AccountMailer;

/** Zapamiętuje ostatni link aktywacyjny wysłany na dany adres, żeby testy mogły go „kliknąć”. */
public class RecordingAccountMailer implements AccountMailer {

    private final Map<String, String> lastLinkByEmail = new ConcurrentHashMap<>();

    @Override
    public void sendActivationLink(String email, String displayName, String username, String activationLink,
            Instant expiresAt) {
        lastLinkByEmail.put(email, activationLink);
    }

    public String lastTokenFor(String email) {
        String link = lastLinkByEmail.get(email);
        if (link == null) {
            throw new IllegalStateException("Nie wysłano linku na " + email);
        }
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        RecordingAccountMailer recordingAccountMailer() {
            return new RecordingAccountMailer();
        }
    }
}
