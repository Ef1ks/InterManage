package com.intermanage.backend.mail.component;

import java.nio.charset.StandardCharsets;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.intermanage.backend.mail.entity.OutgoingMail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/** Wysyłka przez serwer SMTP ze {@code spring.mail.*} (Brevo albo dowolny inny relay). */
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender sender;
    private final InternetAddress from;
    private final InternetAddress replyTo;

    public SmtpMailTransport(JavaMailSender sender, InternetAddress from, InternetAddress replyTo) {
        this.sender = sender;
        this.from = from;
        this.replyTo = replyTo;
    }

    @Override
    public void send(OutgoingMail mail) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        if (replyTo != null) {
            helper.setReplyTo(replyTo);
        }
        helper.setTo(new InternetAddress(mail.getRecipientEmail(), mail.getRecipientName(),
                StandardCharsets.UTF_8.name()));
        helper.setSubject(mail.getSubject());
        helper.setText(mail.getTextBody(), mail.getHtmlBody());
        sender.send(message);
    }
}
