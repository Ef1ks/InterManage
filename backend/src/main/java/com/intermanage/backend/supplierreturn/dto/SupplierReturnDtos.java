package com.intermanage.backend.supplierreturn.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.collector.dto.CollectorListDetails;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.supplierreturn.entity.SupplierReturn;
import com.intermanage.backend.supplierreturn.entity.SupplierReturnStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Zwroty do dostawcy. */
public final class SupplierReturnDtos {

    private SupplierReturnDtos() {
    }

    public record SupplierRef(UUID id, String code, String name, String nip) {
    }

    /** Dokument z raportu dokumentów: bon dostawy (PZ) albo korekty (WZ, PK). */
    public record DocumentRef(UUID id, DocumentType type, String number, String bonNumber, LocalDateTime documentDate,
            String supplierDocumentNumber, BigDecimal purchaseValue) {
    }

    public record InvoiceRef(UUID id, String invoiceNumber, LocalDate issueDate, BigDecimal netAmount) {
    }

    public record Summary(UUID id, SupplierReturnStatus status, SupplierReturn.Source source, SupplierRef supplier,
            String note, Instant createdAt, String createdBy, Instant updatedAt, int lineCount,
            BigDecimal totalQuantity, DocumentRef delivery, DocumentRef correction, InvoiceRef invoice) {
    }

    /** {@code automatic}: zrobione przez aplikację (np. korekta podpięta po EAN-ach). */
    public record Event(String kind, String detail, Instant createdAt, String createdBy, boolean automatic) {
    }

    public record Details(Summary summary, CollectorListDetails items, List<Event> events) {
    }

    /** Kandydat do podpięcia z liczbą artykułów wspólnych ze zwrotem. */
    public record DocumentCandidate(DocumentRef document, long common) {
    }

    /** {@code common} puste: XML faktury nie jest jeszcze pobrany, więc nie wiadomo, co koryguje. */
    public record InvoiceCandidate(InvoiceRef invoice, Long common) {
    }

    public record Candidates(int lineCount, List<DocumentCandidate> deliveries, List<DocumentCandidate> corrections,
            List<InvoiceCandidate> invoices) {
    }

    public record FromCollectorRequest(@NotNull @Size(min = 1) List<UUID> listIds, @Size(max = 500) String note) {
    }

    public record Line(String itm8, String ean, @NotNull String name, String brand, @NotNull BigDecimal quantity) {
    }

    public record FromProtocolsRequest(@NotNull @Size(min = 1, max = 2000) List<@Valid Line> lines, UUID supplierId,
            @Size(max = 500) String note) {
    }

    public record StatusRequest(@NotNull SupplierReturnStatus status, @Size(max = 300) String comment) {
    }

    public record SupplierRequest(UUID supplierId) {
    }

    public record NoteRequest(@Size(max = 500) String note) {
    }

    /** Pusty identyfikator odpina. */
    public record AttachRequest(UUID id) {
    }
}
