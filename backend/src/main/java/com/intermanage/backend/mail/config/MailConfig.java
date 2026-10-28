package com.intermanage.backend.mail.config;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.intermanage.backend.mail.component.LoggingMailTransport;
import com.intermanage.backend.mail.component.MailTransport;
import com.intermanage.backend.mail.component.SmtpMailTransport;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/** SMTP, gdy podano {@code MAIL_HOST}; bez niego maile trafiają do logów. */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailTransport mailTransport(@Value("${spring.mail.host:}") String host,
            @Value("${app.mail.from-address}") String fromAddress,
            @Value("${app.mail.from-name}") String fromName,
            @Value("${app.mail.reply-to:}") String replyTo,
            ObjectProvider<JavaMailSender> sender) throws UnsupportedEncodingException, AddressException {
        if (host.isBlank()) {
            log.info("MAIL_HOST nie jest ustawiony: maile będą wypisywane w logach zamiast wysyłane");
            return new LoggingMailTransport();
        }
        InternetAddress from = new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name());
        from.validate();
        InternetAddress reply = replyTo.isBlank() ? null : new InternetAddress(replyTo, true);
        log.info("Maile wysyłane przez SMTP {} jako {}", host, fromAddress);
        return new SmtpMailTransport(sender.getObject(), from, reply);
    }
}
