package com.intermanage.backend.supplier.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Karta dostawcy: dane z listy z systemu sklepu (u dodanego ręcznie wpisane w aplikacji), strona, telefon, e-mail,
 * notatki i kontakty. {@code listReportGeneratedAt} to data listy, z której dane pochodzą (brak u dodanego ręcznie).
 * Strona, telefon, e-mail, notatki i kontakty są wspólne dla sklepów firmy: {@code sharedWithStores} to pozostałe
 * aktywne sklepy z tym samym NIP-em (puste, gdy sklep nie ma NIP-u albo jest jedynym sklepem firmy).
 */
public record SupplierDetails(
        UUID id,
        String code,
        String keyword,
        String name,
        String nip,
        String address,
        String postalCode,
        String city,
        String origin,
        boolean manual,
        LocalDateTime listReportGeneratedAt,
        Instant createdAt,
        String createdBy,
        String website,
        String email,
        String phone,
        String notes,
        Instant detailsUpdatedAt,
        String detailsUpdatedBy,
        List<SupplierContactResponse> contacts,
        List<String> sharedWithStores) {
}
