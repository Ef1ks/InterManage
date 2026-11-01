package com.intermanage.backend.mail.entity;

/** Rodzaj maila. Nazwa stałej trafia do bazy, więc istniejących nie zmieniamy. */
public enum MailKind {

    ACCOUNT_ACTIVATION(true),
    BACKUP_FAILED(false);

    private final boolean secret;

    MailKind(boolean secret) {
        this.secret = secret;
    }

    /** Treść zawiera jednorazowy link albo inny sekret: po wysłaniu nie zostaje w bazie. */
    public boolean isSecret() {
        return secret;
    }
}
