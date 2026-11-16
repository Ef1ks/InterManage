package com.intermanage.backend.basedelivery.dto;

import java.time.LocalDate;

/**
 * Dokument dostawy z bazy (BL) na liście z portalu Grupy Muszkieterów; {@code loaded} = sklep ma ten bon już wgrany.
 */
public record BasePortalDocument(String number, LocalDate date, boolean loaded) {
}
