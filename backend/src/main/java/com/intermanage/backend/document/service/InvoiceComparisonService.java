package com.intermanage.backend.document.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.CorrectedDelivery.CorrectedLine;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.dto.InvoiceComparison.Row;
import com.intermanage.backend.document.dto.InvoiceComparison.Status;
import com.intermanage.backend.document.dto.InvoiceComparison;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.entity.InvoiceComparisonMatch;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.InvoiceComparisonMatchRepository;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.service.KsefInvoiceService;
import com.intermanage.backend.product.repository.ProductEanRepository;

/**
 * Szuka, w którym artykule przyjęcie PZ różni się od faktur KSeF: XML faktur (z bazy, za pierwszym razem z KSeF)
 * daje pozycje z kodem EAN, które łączą się z artykułami przyjęcia po ITM8 (EAN z linii przyjęcia i wszystkie EAN-y
 * artykułu z kartoteki). Przyjęcie jest po korektach, a z faktur korygujących wiersze "przed korektą" odejmujemy,
 * więc faktura z korektą od dostawcy porównuje się z przyjęciem po korekcie.
 * <p>
 * Czego EAN nie połączy (jedna pozycja przyjęcia rozbita na fakturze na dwie, zły kod od dostawcy, pozycja bez kodu),
 * łączy się ręcznie ({@link InvoiceComparisonMatch}): zaznaczone pozycje faktury i artykuły przyjęcia porównują się
 * razem w jednym wierszu. Pozycja faktury z ręcznego połączenia nie łączy się już sama po EAN-ie.
 */
@Service
public class InvoiceComparisonService {

    private static final String EAN_KEY = "ean:";
    private static final String NAME_KEY = "name:";
    /** Węzeł artykułu przyjęcia w grupowaniu (klucze pozycji faktury mają własne przedrostki). */
    private static final String ITM8_NODE = "itm8:";

    private final TransactionTemplate readOnly;
    private final DocumentRepository documents;
    private final DeliveryCorrectionService corrections;
    private final DocumentKsefLinkService ksefLinks;
    private final ProductEanRepository productEans;
    private final KsefInvoiceService ksefInvoices;
    private final KsefInvoiceReader reader;
    private final InvoiceComparisonMatchRepository matches;
    private final AppUserRepository users;

    public InvoiceComparisonService(PlatformTransactionManager transactionManager, DocumentRepository documents,
            DeliveryCorrectionService corrections, DocumentKsefLinkService ksefLinks,
            ProductEanRepository productEans, KsefInvoiceService ksefInvoices, KsefInvoiceReader reader,
            InvoiceComparisonMatchRepository matches, AppUserRepository users) {
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.documents = documents;
        this.corrections = corrections;
        this.ksefLinks = ksefLinks;
        this.productEans = productEans;
        this.ksefInvoices = ksefInvoices;
        this.reader = reader;
        this.matches = matches;
        this.users = users;
    }

    /** Ręczne połączenie skopiowane z encji (porównanie liczy się poza transakcją). */
    private record Match(UUID id, List<String> invoiceKeys, List<String> itm8s) {
    }

    private record Snapshot(CorrectedDelivery corrected, List<LinkedKsefInvoice> invoices, Map<String, String> itm8ByEan,
            List<Match> matches) {
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

    /**
     * Łączy pozycje faktury ({@code invoiceKeys} z wierszy porównania) z artykułami przyjęcia ({@code itm8s}, po
     * korektach): od teraz porównują się razem.
     */
    @Transactional
    public UUID match(UUID storeId, String typeCode, UUID id, List<String> invoiceKeys, List<String> itm8s,
            UUID userId) {
        Document delivery = delivery(storeId, typeCode, id);
        Set<String> articles = corrected(storeId, delivery).lines().stream().map(CorrectedLine::itm8)
                .collect(Collectors.toSet());
        List<String> keys = invoiceKeys.stream().map(String::strip).distinct().toList();
        for (String key : keys) {
            if (!key.startsWith(EAN_KEY) && !key.startsWith(NAME_KEY)) {
                throw ApiException.badRequest("Nieznana pozycja faktury: " + key);
            }
        }
        List<String> codes = itm8s.stream().map(String::strip).distinct().toList();
        for (String itm8 : codes) {
            if (!articles.contains(itm8)) {
                throw ApiException.badRequest("Artykułu " + itm8 + " nie ma w przyjęciu");
            }
        }
        return matches.save(new InvoiceComparisonMatch(delivery, keys, codes,
                userId != null ? users.getReferenceById(userId) : null)).getId();
    }

    /** Rozłącza (znacznik); „Przywróć” to {@link #restoreMatch}. */
    @Transactional
    public void unmatch(UUID storeId, String typeCode, UUID id, UUID matchId, UUID userId) {
        Document delivery = delivery(storeId, typeCode, id);
        matches.findInDocument(delivery.getId(), matchId).filter(match -> match.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono połączenia"))
                .markRemoved(userId != null ? users.getReferenceById(userId) : null);
    }

    @Transactional
    public void restoreMatch(UUID storeId, String typeCode, UUID id, UUID matchId) {
        Document delivery = delivery(storeId, typeCode, id);
        matches.findInDocument(delivery.getId(), matchId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono połączenia"))
                .restore();
    }

    private Document delivery(UUID storeId, String typeCode, UUID id) {
        Document delivery = documents.findWithLines(storeId, DocumentType.fromCode(typeCode)
                        .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu")), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (delivery.getType() != DocumentType.PZ) {
            throw ApiException.badRequest("Z fakturą porównuje się przyjęcia towaru");
        }
        return delivery;
    }

    private CorrectedDelivery corrected(UUID storeId, Document delivery) {
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(List.of(delivery));
        return corrections.corrected(delivery,
                corrections.correctionDocuments(storeId, links.getOrDefault(delivery.getId(), List.of())));
    }

    private Snapshot snapshot(UUID storeId, String typeCode, UUID id) {
        Document delivery = delivery(storeId, typeCode, id);
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
        List<Match> active = matches.findActive(delivery.getId()).stream()
                .map(match -> new Match(match.getId(), List.copyOf(match.getInvoiceKeys()),
                        List.copyOf(match.getItm8s())))
                .toList();
        return new Snapshot(corrected, invoices, itm8ByEan, active);
    }

    /**
     * Sumy po artykułach z obu stron, połączone w grupy (EAN i ręczne połączenia), i wiersze porównania, najpierw
     * różnice od największej.
     */
    private static InvoiceComparison compare(Snapshot snapshot, List<String> compared,
            List<InvoiceComparison.Unavailable> unavailable, List<KsefInvoiceReader.Line> invoiceLines) {
        Map<String, Side> delivery = new LinkedHashMap<>();
        for (CorrectedLine line : snapshot.corrected().lines()) {
            delivery.computeIfAbsent(line.itm8(), itm8 -> new Side(line.ean(), line.name()))
                    .add(line.quantity(), line.purchaseValue());
        }
        Map<String, Side> invoice = new LinkedHashMap<>();
        BigDecimal invoiceNet = BigDecimal.ZERO;
        for (KsefInvoiceReader.Line line : invoiceLines) {
            BigDecimal sign = line.beforeCorrection() ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            BigDecimal quantity = line.quantity() != null ? line.quantity().multiply(sign) : null;
            BigDecimal net = line.net() != null ? line.net().multiply(sign) : null;
            invoiceNet = invoiceNet.add(net != null ? net : BigDecimal.ZERO);
            boolean coded = line.gtin() != null && !line.gtin().isBlank();
            invoice.computeIfAbsent(invoiceKey(line), key -> new Side(coded ? line.gtin() : null, line.name()))
                    .add(quantity, net);
        }

        Groups groups = new Groups();
        delivery.keySet().forEach(itm8 -> groups.add(ITM8_NODE + itm8));
        Set<String> manualKeys = snapshot.matches().stream().flatMap(match -> match.invoiceKeys().stream())
                .collect(Collectors.toSet());
        for (String key : invoice.keySet()) {
            groups.add(key);
            String itm8 = key.startsWith(EAN_KEY) ? snapshot.itm8ByEan().get(key.substring(EAN_KEY.length())) : null;
            if (itm8 != null && !manualKeys.contains(key)) {
                groups.union(key, ITM8_NODE + itm8);
            }
        }
        for (Match match : snapshot.matches()) {
            String first = null;
            for (String node : nodes(match)) {
                groups.add(node);
                if (first == null) {
                    first = node;
                } else {
                    groups.union(first, node);
                }
            }
        }
        Map<String, List<UUID>> matchIds = new HashMap<>();
        for (Match match : snapshot.matches()) {
            nodes(match).stream().findFirst().ifPresent(node ->
                    matchIds.computeIfAbsent(groups.find(node), root -> new ArrayList<>()).add(match.id()));
        }

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : groups.components().entrySet()) {
            List<String> itm8s = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (String node : group.getValue()) {
                if (node.startsWith(ITM8_NODE) && delivery.containsKey(node.substring(ITM8_NODE.length()))) {
                    itm8s.add(node.substring(ITM8_NODE.length()));
                } else if (invoice.containsKey(node)) {
                    keys.add(node);
                }
            }
            if (itm8s.isEmpty() && keys.isEmpty()) {
                continue;
            }
            rows.add(row(Side.merge(itm8s.stream().map(delivery::get).toList()),
                    Side.merge(keys.stream().map(invoice::get).toList()), itm8s, keys,
                    matchIds.getOrDefault(group.getKey(), List.of())));
        }
        rows.sort(Comparator.comparing((Row row) -> row.status() == Status.MATCH)
                .thenComparing(row -> row.difference() == null ? BigDecimal.ZERO : row.difference().abs(),
                        Comparator.reverseOrder()));
        BigDecimal deliveryValue = delivery.values().stream().map(side -> side.value)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new InvoiceComparison(compared, unavailable, invoiceNet.setScale(2, RoundingMode.HALF_UP),
                deliveryValue, rows);
    }

    private static List<String> nodes(Match match) {
        List<String> nodes = new ArrayList<>(match.invoiceKeys());
        match.itm8s().forEach(itm8 -> nodes.add(ITM8_NODE + itm8));
        return nodes;
    }

    /** Wiersz grupy: {@code ours}/{@code theirs} {@code null}, gdy po tej stronie nic nie ma. */
    private static Row row(Side ours, Side theirs, List<String> itm8s, List<String> keys, List<UUID> matchIds) {
        String itm8 = itm8s.isEmpty() ? null : itm8s.getFirst();
        if (theirs == null) {
            return new Row(Status.MISSING_ON_INVOICE, ours.ean, itm8, ours.name, null, ours.quantity, ours.price(),
                    ours.value, null, null, null, ours.value.negate(), itm8s, keys, matchIds);
        }
        if (ours == null) {
            Status status = keys.stream().allMatch(key -> key.startsWith(NAME_KEY)) ? Status.NO_CODE
                    : Status.MISSING_IN_DELIVERY;
            return new Row(status, theirs.ean, null, theirs.name, theirs.name, null, null, null, theirs.quantity,
                    theirs.price(), theirs.value, theirs.value, itm8s, keys, matchIds);
        }
        BigDecimal difference = theirs.value.subtract(ours.value).setScale(2, RoundingMode.HALF_UP);
        Status status = difference.abs().compareTo(new BigDecimal("0.01")) <= 0 ? Status.MATCH
                : ours.quantity.compareTo(theirs.quantity) == 0 ? Status.PRICE : Status.QUANTITY;
        return new Row(status, ours.ean != null ? ours.ean : theirs.ean, itm8, ours.name, theirs.name, ours.quantity,
                ours.price(), ours.value, theirs.quantity, theirs.price(), theirs.value, difference, itm8s, keys,
                matchIds);
    }

    /** Klucz pozycji faktury: EAN bez zer wiodących, a bez kodu - nazwa. */
    private static String invoiceKey(KsefInvoiceReader.Line line) {
        if (line.gtin() != null && !line.gtin().isBlank()) {
            return EAN_KEY + code(line.gtin());
        }
        return NAME_KEY + (line.name() != null ? line.name().strip() : "");
    }

    /** Suma ilości i wartości jednego artykułu (albo grupy) po jednej stronie. */
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

        /** Kilka stron w jedną: nazwy połączone „+”, EAN pierwszy znany; {@code null} bez stron. */
        static Side merge(List<Side> sides) {
            if (sides.isEmpty()) {
                return null;
            }
            if (sides.size() == 1) {
                return sides.getFirst();
            }
            String ean = sides.stream().map(side -> side.ean).filter(Objects::nonNull).findFirst().orElse(null);
            String name = sides.stream().map(side -> side.name).filter(Objects::nonNull).distinct()
                    .collect(Collectors.joining(" + "));
            Side merged = new Side(ean, name.isEmpty() ? null : name);
            sides.forEach(side -> merged.add(side.quantity, side.value));
            return merged;
        }
    }

    /** Grupowanie węzłów (union-find) z zachowaniem kolejności dodania. */
    private static final class Groups {
        private final Map<String, String> parent = new LinkedHashMap<>();

        void add(String node) {
            parent.putIfAbsent(node, node);
        }

        String find(String node) {
            String root = node;
            while (!parent.get(root).equals(root)) {
                root = parent.get(root);
            }
            parent.put(node, root);
            return root;
        }

        void union(String a, String b) {
            add(a);
            add(b);
            String rootA = find(a);
            String rootB = find(b);
            if (!rootA.equals(rootB)) {
                parent.put(rootB, rootA);
            }
        }

        /** Węzły każdej grupy w kolejności dodania; kluczem jest korzeń. */
        Map<String, List<String>> components() {
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String node : new ArrayList<>(parent.keySet())) {
                result.computeIfAbsent(find(node), root -> new ArrayList<>()).add(node);
            }
            return result;
        }
    }

    /** EAN bez zer wiodących: GTIN-14 z faktury ("05901234123457") = EAN-13 z raportu. */
    private static String code(String ean) {
        String trimmed = ean.strip().replaceFirst("^0+", "");
        return trimmed.isEmpty() ? "0" : trimmed;
    }
}
