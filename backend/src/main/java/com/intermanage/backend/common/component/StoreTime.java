package com.intermanage.backend.common.component;

import java.time.ZoneId;

/** Czas sklepów: dni i miesiące w raportach liczymy w tej strefie, nie w strefie serwera. */
public final class StoreTime {

    public static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private StoreTime() {
    }
}
