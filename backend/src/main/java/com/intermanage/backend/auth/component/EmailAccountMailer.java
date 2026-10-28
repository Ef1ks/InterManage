package com.intermanage.backend.auth.component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.intermanage.backend.auth.service.AccountMailer;
import com.intermanage.backend.mail.component.MailContent;
import com.intermanage.backend.mail.component.MailContent.Detail;
import com.intermanage.backend.mail.entity.MailKind;
import com.intermanage.backend.mail.service.MailService;

/** Maile o koncie przez kolejkę maili ({@link MailService}). */
@Component
public class EmailAccountMailer implements AccountMailer {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
            .withZone(ZoneId.of("Europe/Warsaw"));

    private final MailService mail;

    public EmailAccountMailer(MailService mail) {
        this.mail = mail;
    }

    @Override
    public void sendActivationLink(String email, String displayName, String username, String activationLink,
            Instant expiresAt) {
        MailContent content = new MailContent()
                .heading("Zaproszenie do Intermanage")
                .paragraph("Dzień dobry, " + displayName + ".")
                .paragraph("Masz nowe konto w Intermanage, aplikacji do pracy w sklepie. Aktywuj je i ustaw "
                        + "własne hasło.")
                .button("Aktywuj konto", activationLink)
                .details(new Detail("Login", username), new Detail("Link ważny do", DATE_TIME.format(expiresAt)))
                .note("Link działa tylko raz. Gdy wygaśnie, poproś właściciela o nowy. Jeśli nie spodziewasz się "
                        + "tego zaproszenia, zignoruj tę wiadomość.");
        mail.queue(MailKind.ACCOUNT_ACTIVATION, email, displayName, "Zaproszenie do Intermanage", content);
    }
}
