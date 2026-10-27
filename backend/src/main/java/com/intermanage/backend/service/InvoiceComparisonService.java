package com.intermanage.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.component.KsefInvoiceReader;
import com.intermanage.backend.dto.CorrectedDelivery;
import com.intermanage.backend.dto.CorrectedDelivery.CorrectedLine;
import com.intermanage.backend.dto.InvoiceComparison;
import com.intermanage.backend.dto.InvoiceComparison.Row;
import com.intermanage.backend.dto.InvoiceComparison.Status;
import com.intermanage.backend.dto.LinkedKsefInvoice;
import com.intermanage.backend.entity.DeliveryCorrection;
import com.intermanage.backend.entity.Document;
import com.intermanage.backend.entity.DocumentType;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.repository.DocumentRepository;
import com.intermanage.backend.repository.ProductEanRepository;

/**
 * Szuka, w którym artykule przyjęcie PZ różni się od faktur KSeF: XML faktur (z bazy, za pierwszym razem z KSeF)
 * daje pozycje z kodem EAN, które łączą się z artykułami przyjęcia po ITM8 (EAN z linii przyjęcia i wszystkie EAN-y
 * artykułu z kartoteki). Przyjęcie jest po korektach, a z faktur korygujących wiersze "przed korektą" odejmujemy,
 * więc faktura z korektą od dostawcy porównuje się z przyjęciem po korekcie.
 */
@Service
public class InvoiceComparisonService {

    private final TransactionTemplate readOnly;
    private final DocumentRepository documents;
    private final DeliveryCorrectionService corrections;
    private final DocumentKsefLinkService ksefLinks;
    private final ProductEanRepository productEans;
    private final KsefInvoiceService ksefInvoices;
    private final KsefInvoiceReader reader;

    public InvoiceComparisonService(PlatformTransactionManager transactionManager, DocumentRepository documents,
            DeliveryCorrectionService corrections, DocumentKsefLinkService ksefLinks,
            ProductEanRepository productEans, KsefInvoiceService ksefInvoices, KsefInvoiceReader reader) {
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.documents = documents;
        this.corrections = corrections;
        this.ksefLinks = ksefLinks;
        this.productEans = productEans;
        this.ksefInvoices = ksefInvoices;
        this.reader = reader;
    }

    private record Snapshot(CorrectedDelivery corrected, List<LinkedKsefInvoice> invoices, Map<String, String> itm8ByEan) {
    }

    /**
     * Bez wspólnej transakcji: każde pobranie XML z KSeF zapisuje się samo, a faktura, której teraz nie da się pobrać
     * (limit KSeF), trafia do {@code unavailable} zamiast przerywać porównanie.
     */
    public InvoiceComparison compare(UUID storeId, String typeCode, UUID id) {
        Snapshot snapshot = Objects.requireNonNull(readOnly.execute(status -> snapshot(storeId, typeCode, id)));
        if (snapshot.invoices().isEmpty()) {
            throw ApiException.badRequest("Przyjęcie nie ma faktury z KSeF");
        }
        List<String> compared = new ArrayList<>();
        List<InvoiceComparison.Unavailable> unavailable = new ArrayList<>();
        List<KsefInvoiceReader.Line> invoiceLines = new ArrayList<>();
        for (LinkedKsefInvoice invoice : snapshot.invoices()) {
            try {
                byte[] xml = ksefInvoices.xml(storeId, invoice.id()).content();
                invoiceLines.addAll(reader.read(xml).lines());
                compared.add(invoice.invoiceNumber());
            } catch (ApiException e) {
                unavailable.add(new InvoiceComparison.Unavailable(invoice.invoiceNumber(), e.getMessage()));
            } catch (KsefInvoiceReader.InvalidInvoiceException e) {
                unavailable.add(new InvoiceComparison.Unavailable(invoice.invoiceNumber(),
                        "Nie udało się odczytać faktury: " + e.getMessage()));
            }
        }
        return compare(snapshot, compared, unavailable, invoiceLines);
    }

    private Snapshot snapshot(UUID storeId, String typeCode, UUID id) {
        Document delivery = documents.findWithLines(storeId, DocumentType.fromCode(typeCode)
                        .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu")), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (delivery.getType() != DocumentType.PZ) {
            throw ApiException.badRequest("Z fakturą porównuje się przyjęcia towaru");
        }
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(List.of(delivery));
        CorrectedDelivery corrected = corrections.corrected(delivery,
                corrections.correctionDocuments(storeId, links.getOrDefault(delivery.getId(), List.of())));
        List<LinkedKsefInvoice> invoices = ksefLinks.invoicesWithCorrections(storeId, List.of(delivery), links)
                .getOrDefault(delivery.getId(), List.of());
        Map<String, String> itm8ByEan = new HashMap<>();
        List<String> itm8s = corrected.lines().stream().map(CorrectedLine::itm8).distinct().toList();
        if (!itm8s.isEmpty()) {
            productEans.findEans(storeId, itm8s).forEach(pair -> itm8ByEan.put(code(pair.getEan()), pair.getItm8()));
        }
        // EAN z linii przyjęcia ma pierwszeństwo przed kartoteką.
        corrected.lines().stream().filter(line -> line.ean() != null)
                .forEach(line -> itm8ByEan.put(code(line.ean()), line.itm8()));
        return new Snapshot(corrected, invoices, itm8ByEan);
    }

    /** Sumy po artykułach z obu stron i wiersze porównania, najpierw różnice od największej. */
    private static InvoiceComparison compare(Snapshot snapshot, List<String> compared,
            List<InvoiceComparison.Unavailable> unavailable, List<KsefInvoiceReader.Line> invoiceLines) {
        Map<String, Side> delivery = new LinkedHashMap<>();
        for (CorrectedLine line : snapshot.corrected().lines()) {
            delivery.computeIfAbsent(line.itm8(), itm8 -> new Side(line.ean(), line.name()))
                    .add(line.quantity(), line.purchaseValue());
        }
        Map<String, Side> invoice = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        BigDecimal invoiceNet = BigDecimal.ZERO;
        for (KsefInvoiceReader.Line line : invoiceLines) {
            BigDecimal sign = line.beforeCorrection() ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            BigDecimal quantity = line.quantity() != null ? line.quantity().multiply(sign) : null;
            BigDecimal net = line.net() != null ? line.net().multiply(sign) : null;
            invoiceNet = invoiceNet.add(net != null ? net : BigDecimal.ZERO);
            if (line.gtin() == null || line.gtin().isBlank()) {
                rows.add(new Row(Status.NO_CODE, null, null, line.name(), line.name(), null, null, null, quantity,
                        line.unitNet(), net, net));
                continue;
            }
            String itm8 = snapshot.itm8ByEan().get(code(line.gtin()));
            String key = itm8 != null ? itm8 : "ean:" + code(line.gtin());
            invoice.computeIfAbsent(key, k -> new Side(line.gtin(), line.name())).add(quantity, net);
        }
        for (Map.Entry<String, Side> entry : delivery.entrySet()) {
            Side ours = entry.getValue();
            Side theirs = invoice.remove(entry.getKey());
            rows.add(row(entry.getKey(), ours, theirs));
        }
        for (Map.Entry<String, Side> entry : invoice.entrySet()) {
            Side theirs = entry.getValue();
            BigDecimal value = theirs.value;
            rows.add(new Row(Status.MISSING_IN_DELIVERY, theirs.ean, null, theirs.name, theirs.name, null, null, null,
                    theirs.quantity, theirs.price(), value, value));
        }
        rows.sort(Comparator.comparing((Row row) -> row.status() == Status.MATCH)
                .thenComparing(row -> row.difference() == null ? BigDecimal.ZERO : row.difference().abs(),
                        Comparator.reverseOrder()));
        BigDecimal deliveryValue = delivery.values().stream().map(side -> side.value)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new InvoiceComparison(compared, unavailable, invoiceNet.setScale(2, RoundingMode.HALF_UP),
                deliveryValue, rows);
    }

    private static Row row(String itm8, Side ours, Side theirs) {
        if (theirs == null) {
            return new Row(Status.MISSING_ON_INVOICE, ours.ean, itm8, ours.name, null, ours.quantity, ours.price(),
                    ours.value, null, null, null, ours.value.negate());
        }
        BigDecimal difference = theirs.value.subtract(ours.value).setScale(2, RoundingMode.HALF_UP);
        Status status = difference.abs().compareTo(new BigDecimal("0.01")) <= 0 ? Status.MATCH
                : ours.quantity.compareTo(theirs.quantity) == 0 ? Status.PRICE : Status.QUANTITY;
        return new Row(status, ours.ean != null ? ours.ean : theirs.ean, itm8, ours.name, theirs.name, ours.quantity,
                ours.price(), ours.value, theirs.quantity, theirs.price(), theirs.value, difference);
    }

    /** Suma ilości i wartości jednego artykułu po jednej stronie. */
    private static final class Side {
        final String ean;
        final String name;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;

        Side(String ean, String name) {
            this.ean = ean;
            this.name = name;
        }

        void add(BigDecimal quantity, BigDecimal value) {
            this.quantity = this.quantity.add(quantity != null ? quantity : BigDecimal.ZERO);
            this.value = this.value.add(value != null ? value : BigDecimal.ZERO);
        }

        BigDecimal price() {
            return quantity.signum() == 0 ? null : value.divide(quantity, 4, RoundingMode.HALF_UP);
        }
    }

    /** EAN bez zer wiodących: GTIN-14 z faktury ("05901234123457") = EAN-13 z raportu. */
    private static String code(String ean) {
        String trimmed = ean.strip().replaceFirst("^0+", "");
        return trimmed.isEmpty() ? "0" : trimmed;
    }
}
