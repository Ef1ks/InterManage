package com.intermanage.backend.dataentry.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Kod dostawcy w bazie SCA PR z refaktur firmy: {@code code} tak jak w XML (bez faktur: z przypisania),
 * {@code invoices} liczba refaktur z tym kodem, {@code assignment} dostawca przypisany w firmie albo {@code null}.
 */
public record BaseSupplierCodeItem(String code, long invoices, LocalDate lastIssueDate, Assignment assignment) {

    /** {@code supplierId}: dostawca z listy dostawców sklepu, {@code null} przy samej nazwie. */
    public record Assignment(UUID id, String name, String supplierNip, UUID supplierId, String createdBy,
            Instant createdAt) {
    }
}
