package com.intermanage.backend.mail.component;

import com.intermanage.backend.mail.entity.OutgoingMail;

/** Faktyczna wysyłka jednego maila. Wyjątek oznacza nieudaną próbę, którą kolejka ponowi. */
public interface MailTransport {

    void send(OutgoingMail mail) throws Exception;
}
