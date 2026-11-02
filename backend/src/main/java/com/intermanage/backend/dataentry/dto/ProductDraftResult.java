package com.intermanage.backend.dataentry.dto;

import java.util.List;
import java.util.UUID;

/**
 * Wynik potwierdzenia pliku CADBEZP: {@code createdIds} nowe szkice artykułów (do „Cofnij”), {@code knownCount}
 * artykuły, które już są w kartotece (w pliku jako zmienione), {@code withoutEanCount} wiersze bez EAN-u (kod
 * zastępczy), których raport kartoteki nie pozwoli dopasować.
 */
public record ProductDraftResult(List<UUID> createdIds, int knownCount, int withoutEanCount) {
}
