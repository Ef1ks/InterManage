package com.intermanage.backend.basedelivery.dto;

import java.time.Instant;
import java.util.List;

/**
 * Wprowadzenie bonu z bazy do Mercalys: dostawcy bonu z numerem zamówienia i tymczasowym bonem dostawy z Mercalys.
 *
 * @param suppliers dostawcy plików bonu (538, 88110…), także ci, których pliku w tym bonie nie ma
 */
public record BaseReceivingResponse(String bonNumber, List<Supplier> suppliers) {

    /** @param order {@code null}: plik dostawcy jeszcze nie wszedł (albo bon go nie ma) */
    public record Supplier(String code, String name, Order order, Note note) {
    }

    /** @param enteredBy kto wpisał numer ręcznie ({@code null} = odczytało makro) */
    public record Order(String orderNumber, Instant createdAt, String enteredBy, Instant closedAt) {
    }

    /** @param content odczytany bon albo {@code null} z opisem w {@code error} */
    public record Note(String fileName, Instant uploadedAt, TemporaryNoteResponse content, String error) {
    }
}
