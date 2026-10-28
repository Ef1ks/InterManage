package com.intermanage.backend.mail.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.intermanage.backend.mail.component.MailContent;
import com.intermanage.backend.mail.component.MailDispatcher;
import com.intermanage.backend.mail.entity.MailKind;
import com.intermanage.backend.mail.entity.OutgoingMail;
import com.intermanage.backend.mail.repository.OutgoingMailRepository;

/**
 * Wejście do wysyłki maili dla wszystkich modułów. Mail trafia do kolejki w bieżącej transakcji, więc wycofana
 * zmiana nie wysyła maila, a wychodzi dopiero po zatwierdzeniu, w tle. Błąd serwera pocztowego nie psuje żądania:
 * kolejka ponawia wysyłkę.
 */
@Service
public class MailService {

    private final OutgoingMailRepository mails;
    private final MailDispatcher dispatcher;

    public MailService(OutgoingMailRepository mails, MailDispatcher dispatcher) {
        this.mails = mails;
        this.dispatcher = dispatcher;
    }

    @Transactional
    public void queue(MailKind kind, String email, String name, String subject, MailContent content) {
        mails.save(new OutgoingMail(kind, email, name, subject, content.text(), content.html(subject)));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatcher.wakeUp();
            }
        });
    }
}
