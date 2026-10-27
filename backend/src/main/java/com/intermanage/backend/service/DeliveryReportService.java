package com.intermanage.backend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.component.XlsxWriter;
import com.intermanage.backend.component.XlsxWriter.Cell;
import com.intermanage.backend.component.XlsxWriter.Column;
import com.intermanage.backend.component.XlsxWriter.Row;
import com.intermanage.backend.dto.CorrectedDelivery;
import com.intermanage.backend.dto.LinkedKsefInvoice;
import com.intermanage.backend.entity.DeliveryCorrection;
import com.intermanage.backend.entity.Document;
import com.intermanage.backend.entity.DocumentType;
import com.intermanage.backend.entity.Nomenclature;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.repository.DocumentRepository;
import com.intermanage.backend.repository.NomenclatureRepository;

/**
 * Raport przyjęć PZ za okres w Excelu. Wiersz przyjęcia: data i numer bonu, numer faktury, dostawca, zakup netto
 * z raportu, przypięte korekty z ich wartością i zakup netto po korektach. Pod nim, wcięte o kolumnę i zgrupowane
 * (da się je zwinąć), zakupy netto po korektach według rejonów nomenklatury. Na końcu suma okresu, też według rejonów.
 */
@Service
public class DeliveryReportService {

    /** Najdłuższy okres raportu w dniach. */
    static final int MAX_DAYS = 366;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String WITHOUT_NOMENCLATURE = "Bez nomenklatury";
    private static final List<Column> COLUMNS = List.of(
            new Column("Data bonu", 12),
            new Column("Nr bonu", 22),
            new Column("Nr faktury", 22),
            new Column("Dostawca", 36),
            new Column("Zakup netto zł", 15),
            new Column("Korekty", 56),
            new Column("Korekty netto zł", 16),
            new Column("Zakup netto po korektach zł", 26));

    private final DocumentRepository documents;
    private final DeliveryCorrectionService corrections;
    private final DocumentKsefLinkService ksefLinks;
    private final NomenclatureRepository nomenclatures;
    private final XlsxWriter xlsxWriter;

    public DeliveryReportService(DocumentRepository documents, DeliveryCorrectionService corrections,
            DocumentKsefLinkService ksefLinks, NomenclatureRepository nomenclatures, XlsxWriter xlsxWriter) {
        this.documents = documents;
        this.corrections = corrections;
        this.ksefLinks = ksefLinks;
        this.nomenclatures = nomenclatures;
        this.xlsxWriter = xlsxWriter;
    }

    /** Przyjęcia z datą bonu od {@code from} do {@code to} włącznie. */
    @Transactional(readOnly = true)
    public DocumentService.ExportFile xlsx(UUID storeId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("Koniec okresu jest przed jego początkiem");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.badRequest("Raport obejmuje najwyżej " + MAX_DAYS + " dni");
        }
        List<Document> deliveries = documents.findWithLinesBetween(storeId, DocumentType.PZ, from.atStartOfDay(),
                to.plusDays(1).atStartOfDay());
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(deliveries);
        Map<UUID, Document> correctionDocuments = corrections.correctionDocuments(storeId,
                        links.values().stream().flatMap(List::stream).toList())
                .stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        Map<UUID, List<LinkedKsefInvoice>> invoices = ksefLinks.invoicesOf(storeId, deliveries);

        List<Delivery> rows = deliveries.stream().map(delivery -> {
            List<Document> ofDelivery = links.getOrDefault(delivery.getId(), List.of()).stream()
                    .map(link -> correctionDocuments.get(link.getCorrection().getId()))
                    .filter(Objects::nonNull).toList();
            return new Delivery(delivery, ofDelivery, byRegion(corrections.corrected(delivery, ofDelivery)),
                    invoices.getOrDefault(delivery.getId(), List.of()));
        }).toList();
        Map<String, String> regionNames = regionNames(storeId, rows);

        List<Row> sheet = new ArrayList<>();
        Map<String, BigDecimal> periodRegions = new TreeMap<>(REGION_ORDER);
        BigDecimal purchaseTotal = BigDecimal.ZERO;
        BigDecimal correctionTotal = BigDecimal.ZERO;
        for (Delivery row : rows) {
            Document delivery = row.document();
            BigDecimal correctionValue = row.corrections().stream().map(Document::getPurchaseValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sheet.add(Row.of(
                    Cell.bold(delivery.getDocumentDate().format(DATE)),
                    Cell.bold(delivery.bonNumber()),
                    Cell.bold(invoiceNumber(row)),
                    Cell.bold(Objects.requireNonNullElse(delivery.getSupplierName(), "różni dostawcy")),
                    Cell.boldMoney(delivery.getPurchaseValue()),
                    row.corrections().isEmpty() ? null : row.corrections().stream()
                            .map(DeliveryReportService::correctionLabel).collect(Collectors.joining("; ")),
                    row.corrections().isEmpty() ? null : Cell.money(correctionValue),
                    Cell.boldMoney(delivery.getPurchaseValue().add(correctionValue))));
            row.regions().forEach((code, value) -> {
                sheet.add(Row.nested(null, regionLabel(code, regionNames), Cell.money(value)));
                periodRegions.merge(code, value, BigDecimal::add);
            });
            purchaseTotal = purchaseTotal.add(delivery.getPurchaseValue());
            correctionTotal = correctionTotal.add(correctionValue);
        }

        if (!rows.isEmpty()) {
            sheet.add(Row.of());
            sheet.add(Row.of(Cell.bold("Razem"), null, null,
                    Cell.bold(rows.size() + " " + deliveriesWord(rows.size())), Cell.boldMoney(purchaseTotal), null,
                    Cell.boldMoney(correctionTotal), Cell.boldMoney(purchaseTotal.add(correctionTotal))));
            periodRegions.forEach((code, value) ->
                    sheet.add(Row.nested(null, regionLabel(code, regionNames), Cell.money(value))));
        } else {
            sheet.add(Row.of("Brak przyjęć towaru w okresie " + from.format(DATE) + " - " + to.format(DATE)));
        }
        return new DocumentService.ExportFile("przyjecia-" + from + "-" + to + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxWriter.writeRows("Przyjęcia", COLUMNS, sheet));
    }

    /** Przyjęcie w raporcie: jego korekty, zakup netto po korektach według rejonów i faktury. */
    private record Delivery(Document document, List<Document> corrections, Map<String, BigDecimal> regions,
            List<LinkedKsefInvoice> invoices) {
    }

    /** Rejony po kodzie, linie bez nomenklatury na końcu (klucz {@code null}). */
    private static final Comparator<String> REGION_ORDER = Comparator.nullsLast(Comparator.naturalOrder());

    /** Zakup netto linii po korektach według rejonu (pierwsze dwie cyfry kodu nomenklatury linii). */
    private static Map<String, BigDecimal> byRegion(CorrectedDelivery corrected) {
        Map<String, BigDecimal> regions = new TreeMap<>(REGION_ORDER);
        for (CorrectedDelivery.CorrectedLine line : corrected.lines()) {
            if (line.purchaseValue() == null) {
                continue;
            }
            String code = line.nomenclatureCode();
            regions.merge(code != null && code.length() >= 2 ? code.substring(0, 2) : null, line.purchaseValue(),
                    BigDecimal::add);
        }
        return regions;
    }

    private Map<String, String> regionNames(UUID storeId, List<Delivery> rows) {
        List<String> codes = rows.stream().flatMap(row -> row.regions().keySet().stream())
                .filter(Objects::nonNull).distinct().toList();
        return codes.isEmpty() ? Map.of()
                : nomenclatures.findByStoreIdAndCodeIn(storeId, codes).stream()
                        .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (a, b) -> a));
    }

    private static String regionLabel(String code, Map<String, String> names) {
        if (code == null) {
            return WITHOUT_NOMENCLATURE;
        }
        String name = names.get(code);
        return name != null ? code + " " + name : code;
    }

    /** Faktury przyjęcia (przypięte albo dopasowane po numerze), a bez nich numer dokumentu dostawcy z raportu. */
    private static String invoiceNumber(Delivery row) {
        if (!row.invoices().isEmpty()) {
            return row.invoices().stream().map(LinkedKsefInvoice::invoiceNumber).distinct()
                    .collect(Collectors.joining(", "));
        }
        return row.document().getSupplierDocumentNumber();
    }

    private static String correctionLabel(Document correction) {
        String supplierDocument = correction.getSupplierDocumentNumber();
        return correction.getType().singular() + " " + correction.getNumber()
                + " z " + correction.getDocumentDate().format(DATE)
                + (supplierDocument != null ? " (dok. dostawcy " + supplierDocument + ")" : "");
    }

    private static String deliveriesWord(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        if (count == 1) {
            return "przyjęcie";
        }
        return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "przyjęcia" : "przyjęć";
    }
}
