package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.service.AccountMailer;
import com.intermanage.backend.mail.component.MailContent;
import com.intermanage.backend.mail.component.MailTransport;
import com.intermanage.backend.mail.entity.MailKind;
import com.intermanage.backend.mail.entity.OutgoingMail;
import com.intermanage.backend.mail.repository.OutgoingMailRepository;
import com.intermanage.backend.mail.service.MailService;

// Bez odstępów między próbami, żeby ponowienia szły od razu.
@SpringBootTest(properties = "app.mail.retry-delays=PT0S,PT0S")
@Import({TestcontainersConfiguration.class, MailOutboxIntegrationTests.Config.class})
class MailOutboxIntegrationTests {

    @Autowired
    MailService mail;

    @Autowired
    AccountMailer accountMailer;

    @Autowired
    OutgoingMailRepository mails;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    FakeTransport transport;

    @BeforeEach
    void reset() {
        transport.sent.clear();
        transport.failuresLeft.set(0);
    }

    @Test
    void activationMailIsSentAfterCommitAndItsLinkIsNotKept() {
        String email = "aktywacja-" + System.nanoTime() + "@example.com";
        transaction.executeWithoutResult(status -> accountMailer.sendActivationLink(email, "Anna Nowak", "anowak",
                "https://intermanage.pl/aktywacja?token=sekret", Instant.parse("2026-10-31T11:00:00Z")));

        Sent sent = await(() -> transport.sent.stream()
                .filter(m -> m.email().equals(email)).findFirst().orElse(null));
        assertThat(sent.subject()).isEqualTo("Zaproszenie do Intermanage");
        assertThat(sent.text()).contains("https://intermanage.pl/aktywacja?token=sekret", "anowak",
                "31.10.2026, 12:00");
        assertThat(sent.html()).contains("href=\"https://intermanage.pl/aktywacja?token=sekret\"");

        OutgoingMail stored = await(() -> sentMail(email));
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getTextBody()).doesNotContain("sekret");
        assertThat(stored.getHtmlBody()).doesNotContain("sekret");
    }

    @Test
    void rolledBackTransactionSendsNothing() {
        String email = "wycofany-" + System.nanoTime() + "@example.com";
        transaction.executeWithoutResult(status -> {
            mail.queue(MailKind.ACCOUNT_ACTIVATION, email, null, "Test", new MailContent().paragraph("x"));
            status.setRollbackOnly();
        });

        assertThat(mails.findAll()).noneMatch(m -> m.getRecipientEmail().equals(email));
    }

    @Test
    void failedSendIsRetriedAndGivenUpAfterLastDelay() {
        transport.failuresLeft.set(1);
        String retried = "ponowiony-" + System.nanoTime() + "@example.com";
        mail.queue(MailKind.ACCOUNT_ACTIVATION, retried, null, "Test", new MailContent().paragraph("treść"));

        OutgoingMail sent = await(() -> sentMail(retried));
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(sent.getLastError()).isNull();

        // Trzy próby (pierwsza + dwa ponowienia) i koniec.
        transport.failuresLeft.set(100);
        String abandoned = "porzucony-" + System.nanoTime() + "@example.com";
        mail.queue(MailKind.ACCOUNT_ACTIVATION, abandoned, null, "Test", new MailContent().paragraph("sekret"));

        OutgoingMail failed = await(() -> mails.findAll().stream()
                .filter(m -> m.getRecipientEmail().equals(abandoned) && m.getFailedAt() != null)
                .findFirst().orElse(null));
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getSentAt()).isNull();
        assertThat(failed.getLastError()).isEqualTo("SMTP nie odpowiada");
        assertThat(failed.getTextBody()).doesNotContain("sekret");
    }

    private OutgoingMail sentMail(String email) {
        return mails.findAll().stream()
                .filter(m -> m.getRecipientEmail().equals(email) && m.getSentAt() != null)
                .findFirst().orElse(null);
    }

    private static <T> T await(Supplier<T> value) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            T result = value.get();
            if (result != null) {
                return result;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Nie doczekano się wyniku w 10 s");
    }

    /** Kopia w chwili wysyłki: po niej treść maila z linkiem jest w encji zastępowana. */
    record Sent(String email, String subject, String text, String html) {
    }

    static class FakeTransport implements MailTransport {

        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public void send(OutgoingMail mail) {
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("SMTP nie odpowiada");
            }
            sent.add(new Sent(mail.getRecipientEmail(), mail.getSubject(), mail.getTextBody(), mail.getHtmlBody()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        FakeTransport fakeTransport() {
            return new FakeTransport();
        }
    }
}
