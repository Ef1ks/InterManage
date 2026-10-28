package com.intermanage.backend.mail.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.intermanage.backend.mail.entity.OutgoingMail;

/** Bez serwera SMTP ({@code MAIL_HOST} pusty, np. lokalnie): mail trafia do logów backendu zamiast do odbiorcy. */
public class LoggingMailTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailTransport.class);

    @Override
    public void send(OutgoingMail mail) {
        log.info("[MAIL] Do: {} <{}> | {}\n{}", mail.getRecipientName(), mail.getRecipientEmail(), mail.getSubject(),
                mail.getTextBody());
    }
}
