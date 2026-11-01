package com.intermanage.backend.backup.component;

import java.nio.file.Path;

/** Zrzut całej bazy zaszyfrowany hasłem. W testach podmieniany, bo wymaga programów pg_dump i gpg. */
public interface DatabaseDumper {

    /** Zapisuje zaszyfrowany zrzut do {@code target}; błąd = {@link BackupException}. */
    void dump(Path target, String password);

    /** Rozszerzenie pliku kopii, np. {@code .dump.gpg}. */
    String extension();
}
