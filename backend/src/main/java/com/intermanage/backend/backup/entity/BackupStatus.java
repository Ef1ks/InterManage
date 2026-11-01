package com.intermanage.backend.backup.entity;

/** Stan próby kopii zapasowej. Nazwa stałej trafia do bazy, więc istniejących nie zmieniamy. */
public enum BackupStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}
