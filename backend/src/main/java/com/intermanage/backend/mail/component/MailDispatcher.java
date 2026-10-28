package com.intermanage.backend.mail.component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.intermanage.backend.mail.entity.OutgoingMail;
import com.intermanage.backend.mail.repository.OutgoingMailRepository;

/**
 * Wysyła maile z kolejki: od razu po zatwierdzeniu transakcji, która je dodała ({@link #wakeUp()}), a zaległe i do
 * ponowienia co {@code app.mail.retry-interval}. Po nieudanej próbie czeka kolejny odstęp z
 * {@code app.mail.retry-delays}, a po ostatnim porzuca maila.
 */
@Component
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);
    private static final int BATCH = 20;

    private final OutgoingMailRepository mails;
    private final MailTransport transport;
    private final List<Duration> retryDelays;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean pending = new AtomicBoolean();

    public MailDispatcher(OutgoingMailRepository mails, MailTransport transport,
            @Value("${app.mail.retry-delays}") Duration[] retryDelays) {
        this.mails = mails;
        this.transport = transport;
        this.retryDelays = List.of(retryDelays);
    }

    /** Wysyłka w tle, żeby żądanie użytkownika nie czekało na serwer pocztowy. */
    public void wakeUp() {
        Thread.ofVirtual().name("mail-dispatch").start(this::sendDue);
    }

    /** Naraz wysyła jeden wątek; wywołanie w trakcie wysyłki sprawia, że po niej kolejka zostanie sprawdzona ponownie. */
    @Scheduled(fixedDelayString = "${app.mail.retry-interval}", initialDelayString = "PT1M")
    public void sendDue() {
        pending.set(true);
        while (pending.get() && lock.tryLock()) {
            try {
                while (pending.getAndSet(false)) {
                    sendBatches();
                }
            } catch (RuntimeException e) {
                log.warn("Wysyłka maili z kolejki przerwana: {}", e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    private void sendBatches() {
        List<OutgoingMail> due;
        do {
            due = mails.findDue(Instant.now(), PageRequest.ofSize(BATCH));
            due.forEach(this::send);
        } while (!due.isEmpty());
    }

    private void send(OutgoingMail mail) {
        try {
            transport.send(mail);
            mail.markSent(Instant.now());
        } catch (Exception e) {
            Instant now = Instant.now();
            int attempt = mail.getAttempts() + 1;
            Instant next = attempt <= retryDelays.size() ? now.plus(retryDelays.get(attempt - 1)) : null;
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            mail.markAttemptFailed(now, error, next);
            if (next == null) {
                log.error("Mail {} do {} porzucony po {} próbach: {}", mail.getKind(), mail.getRecipientEmail(),
                        attempt, error);
            } else {
                log.warn("Mail {} do {} nie wyszedł (próba {}), kolejna o {}: {}", mail.getKind(),
                        mail.getRecipientEmail(), attempt, next, error);
            }
        }
        mails.save(mail);
    }
}
