package com.intermanage.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.dto.CorrectionTotals;
import com.intermanage.backend.dto.InvoiceAmountCheck;
import com.intermanage.backend.dto.LinkedKsefInvoice;
import com.intermanage.backend.entity.DeliveryCorrection;
import com.intermanage.backend.entity.Document;
import com.intermanage.backend.entity.DocumentAmountConfirmation;
import com.intermanage.backend.entity.DocumentType;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.repository.AppUserRepository;
import com.intermanage.backend.repository.DocumentAmountConfirmationRepository;
import com.intermanage.backend.repository.DocumentRepository;

/**
 * Zgodność kwot przyjęć PZ z fakturami KSeF: suma netto faktur przyjęcia (przypiętych, dopasowanych po numerze
 * i faktur jego korekt od dostawcy) i wartość zakupu netto przyjęcia po korektach. Różnica do
 * {@link #TOLERANCE} zł w obie strony to zgodność (zaokrąglenia po stronie dostawcy). Większą różnicę można
 * potwierdzić; potwierdzenie obowiązuje dla zapisanych kwot, więc po zmianie faktur albo przyjęcia różnica wraca do
 * sprawdzenia.
 */
@Service
public class InvoiceAmountCheckService {

    /** Akceptowana różnica kwot netto faktury i przyjęcia, w złotych, w górę lub w dół. */
    public static final BigDecimal TOLERANCE = BigDecimal.ONE;

    private final DocumentAmountConfirmationRepository confirmations;
    private final DocumentRepository documents;
    private final DocumentKsefLinkService ksefLinks;
    private final DeliveryCorrectionService corrections;
    private final AppUserRepository users;

    public InvoiceAmountCheckService(DocumentAmountConfirmationRepository confirmations, DocumentRepository documents,
            DocumentKsefLinkService ksefLinks, DeliveryCorrectionService corrections, AppUserRepository users) {
        this.confirmations = confirmations;
        this.documents = documents;
        this.ksefLinks = ksefLinks;
        this.corrections = corrections;
        this.users = users;
    }

    /**
     * Porównanie dla przyjęć z fakturami spośród podanych dokumentów; {@code invoices}: faktury z
     * {@link DocumentKsefLinkService#invoicesWithCorrections}, {@code totals}: wartości po korektach
     * ({@code null} przy przyjęciu bez korekt).
     */
    @Transactional(readOnly = true)
    public Map<UUID, InvoiceAmountCheck> checks(Collection<Document> page, Map<UUID, List<LinkedKsefInvoice>> invoices,
            Map<UUID, CorrectionTotals> totals) {
        List<Document> deliveries = page.stream()
                .filter(document -> document.getType() == DocumentType.PZ
                        && !invoices.getOrDefault(document.getId(), List.of()).isEmpty())
                .toList();
        if (deliveries.isEmpty()) {
            return Map.of();
        }
        Map<UUID, DocumentAmountConfirmation> confirmed = confirmations
                .findActive(deliveries.stream().map(Document::getId).toList()).stream()
                .collect(Collectors.toMap(confirmation -> confirmation.getDocument().getId(), Function.identity()));
        Map<UUID, InvoiceAmountCheck> result = new HashMap<>();
        for (Document delivery : deliveries) {
            BigDecimal invoiceNet = invoiceNet(invoices.get(delivery.getId()));
            BigDecimal documentValue = documentValue(delivery, totals.get(delivery.getId()));
            BigDecimal difference = invoiceNet.subtract(documentValue);
            DocumentAmountConfirmation confirmation = confirmed.get(delivery.getId());
            boolean valid = confirmation != null && confirmation.covers(invoiceNet, documentValue);
            result.put(delivery.getId(), new InvoiceAmountCheck(invoiceNet, documentValue, difference,
                    difference.abs().compareTo(TOLERANCE) <= 0, valid ? confirmation.getConfirmedAt() : null,
                    valid && confirmation.getConfirmedBy() != null ? confirmation.getConfirmedBy().getDisplayName()
                            : null));
        }
        return result;
    }

    /** Potwierdza obecną różnicę kwot przyjęcia (poprzednie potwierdzenie dostaje znacznik). */
    @Transactional
    public void confirm(UUID storeId, String typeCode, UUID id, UUID userId) {
        Document delivery = delivery(storeId, typeCode, id);
        InvoiceAmountCheck check = check(storeId, delivery);
        if (check == null) {
            throw ApiException.badRequest("Przyjęcie nie ma faktury z KSeF");
        }
        if (check.matches()) {
            throw ApiException.badRequest("Kwoty przyjęcia i faktury się zgadzają");
        }
        var user = userId != null ? users.getReferenceById(userId) : null;
        confirmations.findActive(delivery.getId()).ifPresent(previous -> {
            previous.cancel(user);
            confirmations.flush();
        });
        confirmations.save(new DocumentAmountConfirmation(delivery, check.invoiceNet(), check.documentValue(), user));
    }

    /** Cofa potwierdzenie (znacznik); różnica znowu czeka na sprawdzenie. */
    @Transactional
    public void cancel(UUID storeId, String typeCode, UUID id, UUID userId) {
        Document delivery = delivery(storeId, typeCode, id);
        confirmations.findActive(delivery.getId())
                .orElseThrow(() -> ApiException.notFound("Różnica kwot nie jest potwierdzona"))
                .cancel(userId != null ? users.getReferenceById(userId) : null);
    }

    private InvoiceAmountCheck check(UUID storeId, Document delivery) {
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(List.of(delivery));
        CorrectionTotals totals = corrections.totals(delivery, links.get(delivery.getId()));
        Map<UUID, CorrectionTotals> byDocument = new HashMap<>();
        byDocument.put(delivery.getId(), totals);
        return checks(List.of(delivery), ksefLinks.invoicesWithCorrections(storeId, List.of(delivery), links),
                byDocument).get(delivery.getId());
    }

    private Document delivery(UUID storeId, String typeCode, UUID id) {
        DocumentType type = DocumentType.fromCode(typeCode)
                .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
        Document document = documents.findHeader(storeId, type, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (type != DocumentType.PZ) {
            throw ApiException.badRequest("Kwoty z fakturą porównuje się w przyjęciach towaru");
        }
        return document;
    }

    private static BigDecimal invoiceNet(List<LinkedKsefInvoice> invoices) {
        return invoices.stream().map(LinkedKsefInvoice::netAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal documentValue(Document delivery, CorrectionTotals totals) {
        return (totals != null ? totals.purchaseValue() : delivery.getPurchaseValue()).setScale(2, RoundingMode.HALF_UP);
    }
}
