package com.intermanage.backend.backup.component;

/** Błąd kopii zapasowej z opisem dla właściciela (trafia do historii kopii i do maila). */
public class BackupException extends RuntimeException {

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
