package com.intermanage.backend.store.dto;

import java.util.UUID;

/** Sklep na liście wyboru: w przełączniku sklepów i w formularzu użytkownika. */
public record StoreSummary(UUID id, String number, String name, boolean active) {
}
