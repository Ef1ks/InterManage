package com.intermanage.backend.document.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.component.XlsxWriter.Cell;
import com.intermanage.backend.common.component.XlsxWriter.Column;
import com.intermanage.backend.common.component.XlsxWriter.Row;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.CorrectedDelivery;
import com.intermanage.backend.document.dto.DeliveryReportClosingResponse;
import com.intermanage.backend.document.dto.DeliveryReportClosingsOverview;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.DeliveryReportClosing;
import com.intermanage.backend.document.entity.DeliveryReportClosingDelivery;
import com.intermanage.backend.document.entity.DeliveryReportExcludedSupplier;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.mapper.DeliveryReportMapper;
import com.intermanage.backend.document.repository.DeliveryReportClosingDeliveryRepository;
import com.intermanage.backend.document.repository.DeliveryReportClosingRepository;
import com.intermanage.backend.document.repository.DeliveryReportExcludedSupplierRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Raport przyjęć PZ za okres w Excelu i zamykanie okresów. Wiersz przyjęcia: data i numer bonu, numer faktury,
 * dostawca, zakup netto z raportu, przypięte korekty z ich wartością i zakup netto po korektach. Pod nim, wcięte
 * o kolumnę i zgrupowane (otwierają się zwinięte), zakupy netto po korektach według rejonów nomenklatury.
 * <p>
 * Zamknięcie okresu zapisuje, co poszło w raporcie ({@link DeliveryReportClosingDelivery}). Przyjęcie z zamkniętego
 * okresu, które potem się zmieni (nowa albo odpięta korekta, ponowny import, przyjęcie dopisane później), nie zmienia
 * zamkniętego raportu: różnica czeka i trafia do następnego raportu w sekcji „Zmiany w zamkniętych okresach”, a po
 * jego zamknięciu jest zapisana w nim. Cofnąć można tylko ostatnie zamknięcie (cofnięcie to znacznik), więc zmiany
 * przeniesione do późniejszych raportów zawsze mają swój zamknięty okres.
 * <p>
 * Przyjęć pomijanych dostawców ({@link DeliveryReportExcludedSupplier}) nie ma w raporcie ani wśród dopisanych po
 * zamknięciu. Przyjęcie, które poszło już w zamkniętym raporcie, dalej przenosi swoje zmiany, żeby sumy się zgadzały.
 * Korekty są opisane numerem faktury korygującej (dla księgowości), nie numerem wewnętrznym.
 */
@Service
public class DeliveryReportService {

    /** Najdłuższy okres raportu w dniach. */
    static final int MAX_DAYS = 366;
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZONE);
    private static final String WITHOUT_NOMENCLATURE = "Bez nomenklatury";
    private static final String LOCK_PREFIX = "delivery-report-closing:";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** Rejony po kodzie, linie bez nomenklatury na końcu (klucz {@code null}). */
    private static final Comparator<String> REGION_ORDER = Comparator.nullsLast(Comparator.naturalOrder());
    private static final List<Column> COLUMNS = List.of(
            new Column("Data bonu", 12),
            new Column("Nr bonu", 22),
            new Column("Nr faktury", 22),
            new Column("Dostawca", 36),
            new Column("Zakup netto zł", 15),
            new Column("Korekty", 60),
            new Column("Korekty netto zł", 16),
            new Column("Zakup netto po korektach zł", 26));

    private final DocumentRepository documents;
    private final DeliveryCorrectionService corrections;
    private final DocumentKsefLinkService ksefLinks;
    private final NomenclatureRepository nomenclatures;
    private final DeliveryReportClosingRepository closings;
    private final DeliveryReportClosingDeliveryRepository closingDeliveries;
    private final DeliveryReportExcludedSupplierRepository excludedSuppliers;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final DeliveryReportMapper mapper;
    private final XlsxWriter xlsxWriter;

    public DeliveryReportService(DocumentRepository documents, DeliveryCorrectionService corrections,
            DocumentKsefLinkService ksefLinks, NomenclatureRepository nomenclatures,
            DeliveryReportClosingRepository closings, DeliveryReportClosingDeliveryRepository closingDeliveries,
            DeliveryReportExcludedSupplierRepository excludedSuppliers, SupplierRepository suppliers,
            StoreRepository stores, AppUserRepository users, DeliveryReportMapper mapper, XlsxWriter xlsxWriter) {
        this.documents = documents;
        this.corrections = corrections;
        this.ksefLinks = ksefLinks;
        this.nomenclatures = nomenclatures;
        this.closings = closings;
        this.closingDeliveries = closingDeliveries;
        this.excludedSuppliers = excludedSuppliers;
        this.suppliers = suppliers;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
        this.xlsxWriter = xlsxWriter;
    }

    /**
     * Podgląd raportu: przyjęcia z datą bonu od {@code from} do {@code to} w obecnym stanie i zmiany w zamkniętych
     * okresach, które na niego nie nachodzą. Nic nie zapisuje.
     */
    @Transactional(readOnly = true)
    public DocumentService.ExportFile preview(UUID storeId, LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        List<DeliveryReportExcludedSupplier> excluded = excludedSuppliers.findActive(storeId);
        Set<String> excludedCodes = codes(excluded);
        List<Current> main = current(storeId, deliveriesBetween(storeId, from, to, excludedCodes));
        List<Change> changes = pendingChanges(storeId, from, to, excludedCodes);
        Map<UUID, String> labels = correctionLabels(storeId, main, changes);
        List<Row> sheet = sheet(storeId, main.stream().map(current -> line(current, labels)).toList(),
                changes.stream().map(change -> line(change, labels)).toList(), from, to);
        footer(sheet, excludedLabel(excluded),
                "Podgląd wygenerowany " + DATE_TIME.format(Instant.now()) + " (okres nie jest zamknięty)");
        return file("przyjecia-" + from + "-" + to + ".xlsx", sheet);
    }

    /** Same zmiany w zamkniętych okresach, które czekają na następny raport. */
    @Transactional(readOnly = true)
    public DocumentService.ExportFile pendingChangesFile(UUID storeId) {
        List<DeliveryReportExcludedSupplier> excluded = excludedSuppliers.findActive(storeId);
        List<Change> changes = pendingChanges(storeId, null, null, codes(excluded));
        Map<UUID, String> labels = correctionLabels(storeId, List.of(), changes);
        List<Row> sheet = sheet(storeId, List.of(), changes.stream().map(change -> line(change, labels)).toList(),
                null, null);
        footer(sheet, excludedLabel(excluded), "Wygenerowano " + DATE_TIME.format(Instant.now()));
        return file("przyjecia-zmiany-po-zamknieciu-" + LocalDate.now(ZONE) + ".xlsx", sheet);
    }

    /** Aktywne zamknięcia z liczbą przyjęć zmienionych po każdym z nich i pomijani dostawcy. */
    @Transactional(readOnly = true)
    public DeliveryReportClosingsOverview overview(UUID storeId) {
        List<DeliveryReportClosing> active = closings.findActive(storeId);
        List<DeliveryReportExcludedSupplier> excluded = excludedSuppliers.findActive(storeId);
        Map<UUID, Long> pending = pendingChanges(storeId, null, null, codes(excluded)).stream()
                .collect(Collectors.groupingBy(change -> change.origin().getId(), Collectors.counting()));
        UUID latest = latest(active).map(DeliveryReportClosing::getId).orElse(null);
        List<DeliveryReportClosingResponse> responses = active.stream()
                .map(closing -> mapper.toResponse(closing, pending.getOrDefault(closing.getId(), 0L).intValue(),
                        closing.getId().equals(latest)))
                .toList();
        return new DeliveryReportClosingsOverview(responses,
                (int) pending.values().stream().mapToLong(Long::longValue).sum(),
                excluded.stream().map(mapper::toResponse).toList());
    }

    /**
     * Zastępuje listę dostawców pomijanych w raporcie (po kodzie dostawcy). Usunięci z listy dostają znacznik. Nazwa
     * z listy dostawców sklepu, a gdy dostawcy na niej nie ma, z jego dokumentów.
     */
    @Transactional
    public DeliveryReportClosingsOverview setExcludedSuppliers(UUID storeId, List<String> supplierCodes, UUID userId) {
        documents.lockForImport(LOCK_PREFIX + storeId);
        Set<String> wanted = supplierCodes.stream().map(String::strip)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        AppUser user = user(userId);
        Set<String> present = new HashSet<>();
        for (DeliveryReportExcludedSupplier item : excludedSuppliers.findActive(storeId)) {
            if (wanted.contains(item.getSupplierCode())) {
                present.add(item.getSupplierCode());
            } else {
                item.markRemoved(user);
            }
        }
        List<String> added = wanted.stream().filter(code -> !present.contains(code)).toList();
        if (!added.isEmpty()) {
            Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
            Map<String, String> names = suppliers.findImported(storeId).stream()
                    .collect(Collectors.toMap(Supplier::getCode, Supplier::getName, (a, b) -> a));
            for (String code : added) {
                String name = names.get(code);
                if (name == null) {
                    name = documents.findSupplierNames(storeId, code, Limit.of(1)).stream().findFirst()
                            .orElseThrow(() -> ApiException.badRequest("Nie znaleziono dostawcy o kodzie " + code));
                }
                excludedSuppliers.save(new DeliveryReportExcludedSupplier(store, code, name.strip(), user));
            }
        }
        return overview(storeId);
    }

    /**
     * Zamyka okres: zapisuje przyjęcia z okresu i oczekujące zmiany z wcześniej zamkniętych okresów tak, jak są teraz
     * (to samo, co pokazuje podgląd). Okres nie może nachodzić na inny zamknięty ani sięgać w przyszłość.
     */
    @Transactional
    public DeliveryReportClosingResponse close(UUID storeId, LocalDate from, LocalDate to, UUID userId) {
        validatePeriod(from, to);
        if (to.isAfter(LocalDate.now(ZONE))) {
            throw ApiException.badRequest("Nie można zamknąć okresu, który się jeszcze nie skończył");
        }
        documents.lockForImport(LOCK_PREFIX + storeId);
        List<DeliveryReportClosing> active = closings.findActive(storeId);
        active.stream().filter(closing -> closing.overlaps(from, to)).findFirst().ifPresent(closing -> {
            throw ApiException.conflict("Okres nachodzi na zamknięty raport " + period(closing)
                    + ". Wybierz inne dni albo najpierw cofnij tamto zamknięcie.");
        });
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        List<DeliveryReportExcludedSupplier> excluded = excludedSuppliers.findActive(storeId);
        Set<String> excludedCodes = codes(excluded);
        List<Current> main = current(storeId, deliveriesBetween(storeId, from, to, excludedCodes));
        List<Change> changes = pendingChanges(storeId, from, to, excludedCodes);

        DeliveryReportClosing closing = closings.save(new DeliveryReportClosing(store, from, to, user(userId),
                excludedLabel(excluded)));
        List<DeliveryReportClosingDelivery> rows = new ArrayList<>();
        for (Current delivery : main) {
            rows.add(new DeliveryReportClosingDelivery(closing, delivery.document(), null,
                    delivery.document().getPurchaseValue(), delivery.correctionValue(), delivery.regions(),
                    delivery.correctionIds()));
        }
        for (Change change : changes) {
            rows.add(new DeliveryReportClosingDelivery(closing, change.current().document(), change.origin(),
                    change.purchaseDelta(), change.correctionDelta(), change.regionDeltas(),
                    change.current().correctionIds()));
        }
        closingDeliveries.saveAll(rows);
        return mapper.toResponse(closing, 0, true);
    }

    /** Zamknięty raport tak, jak poszedł: przyjęcia okresu i przeniesione do niego zmiany ze zrzutu, nie z bazy. */
    @Transactional(readOnly = true)
    public DocumentService.ExportFile closedFile(UUID storeId, UUID closingId) {
        DeliveryReportClosing closing = closings.findInStore(storeId, closingId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zamkniętego raportu"));
        List<DeliveryReportClosingDelivery> rows = closingDeliveries.findByClosing(closing.getId());
        List<Document> deliveries = rows.stream().map(DeliveryReportClosingDelivery::getDelivery).distinct().toList();
        Map<UUID, List<LinkedKsefInvoice>> invoices = ksefLinks.invoicesOf(storeId, deliveries);

        // Do zmian: co zamknięcia wiedziały o przyjęciu tuż przed tym raportem (nowe i odpięte korekty).
        List<DeliveryReportClosingDelivery> carried = rows.stream().filter(row -> row.getOriginClosing() != null).toList();
        Map<UUID, List<DeliveryReportClosingDelivery>> earlier = carried.isEmpty() ? Map.of()
                : closingDeliveries.findKnown(
                                carried.stream().map(row -> row.getOriginClosing().getId()).collect(Collectors.toSet()),
                                carried.stream().map(row -> row.getDelivery().getId()).collect(Collectors.toSet()))
                        .stream()
                        .filter(row -> row.getClosing().getClosedAt().isBefore(closing.getClosedAt()))
                        .collect(Collectors.groupingBy(row -> row.getDelivery().getId()));
        Map<UUID, Document> headers = headers(storeId, Stream.concat(rows.stream(),
                        earlier.values().stream().flatMap(List::stream))
                .flatMap(row -> row.getCorrectionIds().stream()).collect(Collectors.toSet()));
        Map<UUID, String> labels = correctionLabels(storeId, headers.values());

        List<ReportLine> main = new ArrayList<>();
        List<ReportLine> changes = new ArrayList<>();
        Comparator<DeliveryReportClosingDelivery> order = Comparator
                .comparing((DeliveryReportClosingDelivery row) -> row.getDelivery().getDocumentDate())
                .thenComparing(row -> row.getDelivery().getNumber());
        for (DeliveryReportClosingDelivery row : rows.stream().sorted(order).toList()) {
            Document delivery = row.getDelivery();
            String invoice = invoiceNumber(delivery, invoices.getOrDefault(delivery.getId(), List.of()));
            Map<String, BigDecimal> regions = new TreeMap<>(REGION_ORDER);
            row.getRegions().forEach(region -> regions.merge(region.regionCode(), region.purchaseValue(),
                    BigDecimal::add));
            if (row.getOriginClosing() == null) {
                List<Document> attached = row.getCorrectionIds().stream().map(headers::get)
                        .filter(Objects::nonNull).sorted(Comparator.comparing(Document::getDocumentDate)).toList();
                main.add(new ReportLine(delivery, invoice, correctionsLabel(attached, labels), row.getPurchaseValue(),
                        attached.isEmpty() ? null : row.getCorrectionValue(), regions));
            } else {
                Known before = known(earlier.getOrDefault(delivery.getId(), List.of()));
                Set<UUID> known = before != null ? before.correctionIds() : Set.of();
                List<Document> added = row.getCorrectionIds().stream().filter(id -> !known.contains(id))
                        .map(headers::get).filter(Objects::nonNull).toList();
                List<Document> removed = known.stream().filter(id -> !row.getCorrectionIds().contains(id))
                        .map(headers::get).filter(Objects::nonNull).toList();
                changes.add(new ReportLine(delivery, invoice,
                        changeLabel(row.getOriginClosing(), before == null, row.getPurchaseValue(), added, removed,
                                labels),
                        row.getPurchaseValue(), row.getCorrectionValue(), regions));
            }
        }
        List<Row> sheet = sheet(storeId, main, changes, closing.getPeriodFrom(), closing.getPeriodTo());
        footer(sheet, closing.getExcludedSuppliers(), "Raport zamknięty " + DATE_TIME.format(closing.getClosedAt())
                + (closing.getClosedBy() != null ? " przez " + closing.getClosedBy().getDisplayName() : "")
                + (closing.getCancelledAt() != null
                        ? ", zamknięcie cofnięte " + DATE_TIME.format(closing.getCancelledAt()) : ""));
        return file("przyjecia-" + closing.getPeriodFrom() + "-" + closing.getPeriodTo() + "-zamkniety.xlsx", sheet);
    }

    /** Cofa ostatnie zamknięcie (znacznik); jego przyjęcia i przeniesione zmiany znowu czekają na raport. */
    @Transactional
    public DeliveryReportClosingResponse cancel(UUID storeId, UUID closingId, UUID userId) {
        documents.lockForImport(LOCK_PREFIX + storeId);
        DeliveryReportClosing closing = closings.findInStore(storeId, closingId)
                .filter(found -> found.getCancelledAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zamkniętego raportu"));
        DeliveryReportClosing latest = latest(closings.findActive(storeId)).orElseThrow();
        if (!latest.getId().equals(closing.getId())) {
            throw ApiException.conflict("Cofnąć można tylko ostatnie zamknięcie (raport " + period(latest)
                    + "), bo późniejsze raporty mogły przejąć zmiany z tego okresu.");
        }
        closing.cancel(user(userId));
        return mapper.toResponse(closing, 0, false);
    }

    /** Przywraca cofnięte zamknięcie, jeśli od tamtej pory nie zamknięto innego raportu. */
    @Transactional
    public DeliveryReportClosingResponse restore(UUID storeId, UUID closingId) {
        documents.lockForImport(LOCK_PREFIX + storeId);
        DeliveryReportClosing closing = closings.findInStore(storeId, closingId)
                .filter(found -> found.getCancelledAt() != null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono cofniętego zamknięcia"));
        for (DeliveryReportClosing other : closings.findActive(storeId)) {
            if (other.getClosedAt().isAfter(closing.getClosedAt()) || other.overlaps(closing.getPeriodFrom(),
                    closing.getPeriodTo())) {
                throw ApiException.conflict("Po cofnięciu zamknięto raport " + period(other)
                        + ", więc tego zamknięcia nie da się już przywrócić. Zamknij okres ponownie.");
            }
        }
        closing.restore();
        return mapper.toResponse(closing, 0, true);
    }

    // --- Obecny stan przyjęć i zmiany po zamknięciu ---

    /** Przyjęcie w obecnym stanie: korekty, zakup netto po korektach według rejonów i faktury. */
    private record Current(Document document, List<Document> corrections, Map<String, BigDecimal> regions,
            List<LinkedKsefInvoice> invoices) {

        BigDecimal correctionValue() {
            return corrections.stream().map(Document::getPurchaseValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        Set<UUID> correctionIds() {
            return corrections.stream().map(Document::getId).collect(Collectors.toSet());
        }
    }

    /** Co zamknięte raporty wiedzą o przyjęciu: suma ich kwot i korekty z ostatniego. */
    private record Known(BigDecimal purchaseValue, BigDecimal correctionValue, Map<String, BigDecimal> regions,
            Set<UUID> correctionIds) {
    }

    /**
     * Przyjęcie z zamkniętego okresu ({@code origin}) zmienione po zamknięciu: różnice względem zamkniętych raportów,
     * nowe i odpięte korekty; {@code added}: przyjęcia nie było w żadnym raporcie (dopisane później).
     */
    private record Change(DeliveryReportClosing origin, Current current, BigDecimal purchaseDelta,
            BigDecimal correctionDelta, Map<String, BigDecimal> regionDeltas, List<Document> addedCorrections,
            List<Document> removedCorrections, boolean added) {
    }

    /** Przyjęcia z okresu bez pomijanych dostawców (przyjęcie kilku dostawców zostaje). */
    private List<Document> deliveriesBetween(UUID storeId, LocalDate from, LocalDate to, Set<String> excluded) {
        return documents.findWithLinesBetween(storeId, DocumentType.PZ, from.atStartOfDay(),
                        to.plusDays(1).atStartOfDay())
                .stream().filter(delivery -> !excluded.contains(delivery.getSupplierCode())).toList();
    }

    private List<Current> current(UUID storeId, List<Document> deliveries) {
        Map<UUID, List<DeliveryCorrection>> links = corrections.correctionsOf(deliveries);
        Map<UUID, Document> correctionDocuments = corrections.correctionDocuments(storeId,
                        links.values().stream().flatMap(List::stream).toList())
                .stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        Map<UUID, List<LinkedKsefInvoice>> invoices = ksefLinks.invoicesOf(storeId, deliveries);
        return deliveries.stream().map(delivery -> {
            List<Document> ofDelivery = links.getOrDefault(delivery.getId(), List.of()).stream()
                    .map(link -> correctionDocuments.get(link.getCorrection().getId()))
                    .filter(Objects::nonNull).toList();
            return new Current(delivery, ofDelivery, byRegion(corrections.corrected(delivery, ofDelivery)),
                    invoices.getOrDefault(delivery.getId(), List.of()));
        }).toList();
    }

    /**
     * Zmiany przyjęć z aktywnych zamknięć, które nie nachodzą na okres {@code [from, to]} (ten pokazuje je w obecnym
     * stanie); {@code null} = ze wszystkich. Kandydaci (zmienieni po zamknięciu) są wybierani w bazie, potem
     * porównywani dokładnie z tym, co poszło w raportach. Przyjęcia pomijanych dostawców ({@code excluded}) tylko,
     * gdy już były w zamkniętym raporcie.
     */
    private List<Change> pendingChanges(UUID storeId, LocalDate from, LocalDate to, Set<String> excluded) {
        List<Change> result = new ArrayList<>();
        for (DeliveryReportClosing closing : closings.findActive(storeId)) {
            if (from != null && closing.overlaps(from, to)) {
                continue;
            }
            List<UUID> ids = documents.findChangedDeliveryIds(storeId, closing.getPeriodFrom().atStartOfDay(),
                    closing.getPeriodTo().plusDays(1).atStartOfDay(), closing.getClosedAt());
            if (ids.isEmpty()) {
                continue;
            }
            Map<UUID, Known> known = new HashMap<>();
            closingDeliveries.findKnown(List.of(closing.getId()), ids).stream()
                    .collect(Collectors.groupingBy(row -> row.getDelivery().getId()))
                    .forEach((deliveryId, rows) -> known.put(deliveryId, known(rows)));
            List<Document> changed = documents.findWithLines(storeId, ids).stream()
                    .filter(delivery -> known.containsKey(delivery.getId())
                            || !excluded.contains(delivery.getSupplierCode()))
                    .sorted(Comparator.comparing(Document::getDocumentDate).thenComparing(Document::getNumber))
                    .toList();
            // Odpięte korekty znamy tylko z zamkniętych raportów - do opisu wystarczą nagłówki.
            Map<UUID, Document> labels = headers(storeId, known.values().stream()
                    .flatMap(item -> item.correctionIds().stream()).collect(Collectors.toSet()));
            for (Current current : current(storeId, changed)) {
                Change change = change(closing, current, known.get(current.document().getId()), labels);
                if (change != null) {
                    result.add(change);
                }
            }
        }
        return result;
    }

    /** Suma wpisów aktywnych zamknięć o przyjęciu, korekty z najpóźniejszego; {@code null}, gdy nie było go nigdzie. */
    private static Known known(List<DeliveryReportClosingDelivery> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        BigDecimal purchase = BigDecimal.ZERO;
        BigDecimal correction = BigDecimal.ZERO;
        Map<String, BigDecimal> regions = new TreeMap<>(REGION_ORDER);
        DeliveryReportClosingDelivery last = null;
        for (DeliveryReportClosingDelivery row : rows) {
            purchase = purchase.add(row.getPurchaseValue());
            correction = correction.add(row.getCorrectionValue());
            row.getRegions().forEach(region -> regions.merge(region.regionCode(), region.purchaseValue(),
                    BigDecimal::add));
            if (last == null || row.getClosing().getClosedAt().isAfter(last.getClosing().getClosedAt())) {
                last = row;
            }
        }
        return new Known(purchase, correction, regions, Set.copyOf(last.getCorrectionIds()));
    }

    /**
     * Różnica obecnego stanu i zamkniętych raportów; {@code null}, gdy nic się nie zmieniło. {@code labels}: nagłówki
     * korekt znanych z raportów (do opisu odpiętych).
     */
    private static Change change(DeliveryReportClosing origin, Current current, Known known,
            Map<UUID, Document> labels) {
        BigDecimal knownPurchase = known != null ? known.purchaseValue() : BigDecimal.ZERO;
        BigDecimal knownCorrection = known != null ? known.correctionValue() : BigDecimal.ZERO;
        Map<String, BigDecimal> knownRegions = known != null ? known.regions() : Map.of();
        Set<UUID> knownCorrections = known != null ? known.correctionIds() : Set.of();

        BigDecimal purchaseDelta = current.document().getPurchaseValue().subtract(knownPurchase);
        BigDecimal correctionDelta = current.correctionValue().subtract(knownCorrection);
        Map<String, BigDecimal> regionDeltas = new TreeMap<>(REGION_ORDER);
        Set<String> codes = new HashSet<>(current.regions().keySet());
        codes.addAll(knownRegions.keySet());
        for (String code : codes) {
            BigDecimal delta = current.regions().getOrDefault(code, BigDecimal.ZERO)
                    .subtract(knownRegions.getOrDefault(code, BigDecimal.ZERO));
            if (delta.signum() != 0) {
                regionDeltas.put(code, delta);
            }
        }
        List<Document> addedCorrections = current.corrections().stream()
                .filter(correction -> !knownCorrections.contains(correction.getId())).toList();
        List<UUID> removed = knownCorrections.stream().filter(id -> !current.correctionIds().contains(id)).toList();
        if (known != null && purchaseDelta.signum() == 0 && correctionDelta.signum() == 0 && regionDeltas.isEmpty()
                && addedCorrections.isEmpty() && removed.isEmpty()) {
            return null;
        }
        return new Change(origin, current, purchaseDelta, correctionDelta, regionDeltas, addedCorrections,
                removed.stream().map(labels::get).filter(Objects::nonNull).toList(), known == null);
    }

    // --- Arkusz ---

    /** Wiersz przyjęcia w arkuszu (z obecnego stanu albo ze zrzutu zamknięcia); {@code correctionValue} null = brak. */
    private record ReportLine(Document delivery, String invoiceNumber, String corrections, BigDecimal purchaseValue,
            BigDecimal correctionValue, Map<String, BigDecimal> regions) {

        BigDecimal total() {
            return correctionValue == null ? purchaseValue : purchaseValue.add(correctionValue);
        }
    }

    private static ReportLine line(Current current, Map<UUID, String> labels) {
        return new ReportLine(current.document(), invoiceNumber(current.document(), current.invoices()),
                correctionsLabel(current.corrections(), labels), current.document().getPurchaseValue(),
                current.corrections().isEmpty() ? null : current.correctionValue(), current.regions());
    }

    private static ReportLine line(Change change, Map<UUID, String> labels) {
        Current current = change.current();
        return new ReportLine(current.document(), invoiceNumber(current.document(), current.invoices()),
                changeLabel(change.origin(), change.added(), change.purchaseDelta(), change.addedCorrections(),
                        change.removedCorrections(), labels),
                change.purchaseDelta(), change.correctionDelta(), change.regionDeltas());
    }

    /**
     * Przyjęcia okresu, a pod nimi (gdy są) zmiany w zamkniętych okresach, każda sekcja z sumą i rejonami.
     * {@code from}/{@code to} tylko do komunikatu o pustym okresie.
     */
    private List<Row> sheet(UUID storeId, List<ReportLine> main, List<ReportLine> changes, LocalDate from,
            LocalDate to) {
        Map<String, String> regionNames = regionNames(storeId, main, changes);
        List<Row> sheet = new ArrayList<>();
        if (!main.isEmpty()) {
            section(sheet, main, regionNames, "Razem", deliveriesWord(main.size()));
        } else if (from != null) {
            sheet.add(Row.of("Brak przyjęć towaru w okresie " + from.format(DATE) + " - " + to.format(DATE)));
        }
        if (!changes.isEmpty()) {
            if (!sheet.isEmpty()) {
                sheet.add(Row.of());
            }
            sheet.add(Row.of(Cell.bold("Zmiany w zamkniętych okresach"), null, null, null, null,
                    "Kwoty to różnice względem zamkniętych raportów"));
            section(sheet, changes, regionNames, "Razem zmiany", changesWord(changes.size()));
        } else if (from == null) {
            sheet.add(Row.of("Brak zmian w zamkniętych okresach"));
        }
        if (!main.isEmpty() && !changes.isEmpty()) {
            BigDecimal purchase = sum(main, ReportLine::purchaseValue).add(sum(changes, ReportLine::purchaseValue));
            BigDecimal correction = sum(main, ReportLine::correctionValue)
                    .add(sum(changes, ReportLine::correctionValue));
            sheet.add(Row.of());
            sheet.add(Row.of(Cell.bold("Razem ze zmianami"), null, null, null, Cell.boldMoney(purchase), null,
                    Cell.boldMoney(correction), Cell.boldMoney(purchase.add(correction))));
        }
        return sheet;
    }

    private static void section(List<Row> sheet, List<ReportLine> lines, Map<String, String> regionNames,
            String totalLabel, String countLabel) {
        Map<String, BigDecimal> regions = new TreeMap<>(REGION_ORDER);
        for (ReportLine line : lines) {
            Document delivery = line.delivery();
            sheet.add(Row.of(
                    Cell.bold(delivery.getDocumentDate().format(DATE)),
                    Cell.bold(delivery.bonNumber()),
                    Cell.bold(line.invoiceNumber()),
                    Cell.bold(Objects.requireNonNullElse(delivery.getSupplierName(), "różni dostawcy")),
                    Cell.boldMoney(line.purchaseValue()),
                    line.corrections(),
                    line.correctionValue() == null ? null : Cell.money(line.correctionValue()),
                    Cell.boldMoney(line.total())));
            line.regions().forEach((code, value) -> {
                sheet.add(Row.nested(null, regionLabel(code, regionNames), Cell.money(value)));
                regions.merge(code, value, BigDecimal::add);
            });
        }
        BigDecimal purchase = sum(lines, ReportLine::purchaseValue);
        BigDecimal correction = sum(lines, ReportLine::correctionValue);
        sheet.add(Row.of());
        sheet.add(Row.of(Cell.bold(totalLabel), null, null, Cell.bold(lines.size() + " " + countLabel),
                Cell.boldMoney(purchase), null, Cell.boldMoney(correction), Cell.boldMoney(purchase.add(correction))));
        regions.forEach((code, value) -> sheet.add(Row.nested(null, regionLabel(code, regionNames), Cell.money(value))));
    }

    private static BigDecimal sum(List<ReportLine> lines, Function<ReportLine, BigDecimal> value) {
        return lines.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

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

    private Map<String, String> regionNames(UUID storeId, List<ReportLine> main, List<ReportLine> changes) {
        List<String> codes = Stream.concat(main.stream(), changes.stream())
                .flatMap(line -> line.regions().keySet().stream())
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
    private static String invoiceNumber(Document delivery, List<LinkedKsefInvoice> invoices) {
        if (!invoices.isEmpty()) {
            return invoices.stream().map(LinkedKsefInvoice::invoiceNumber).distinct().collect(Collectors.joining(", "));
        }
        return delivery.getSupplierDocumentNumber();
    }

    /** {@code labels}: opisy korekt z {@link #correctionLabels}. */
    private static String correctionsLabel(List<Document> corrections, Map<UUID, String> labels) {
        return corrections.isEmpty() ? null
                : corrections.stream().map(correction -> labels.get(correction.getId()))
                        .collect(Collectors.joining("; "));
    }

    /** Opis zmiany: z którego zamkniętego okresu i co się zmieniło. */
    private static String changeLabel(DeliveryReportClosing origin, boolean added, BigDecimal purchaseDelta,
            List<Document> addedCorrections, List<Document> removedCorrections, Map<UUID, String> labels) {
        List<String> parts = new ArrayList<>();
        parts.add("Okres " + period(origin));
        if (added) {
            parts.add("przyjęcie dopisane po zamknięciu");
        } else if (purchaseDelta.signum() != 0) {
            parts.add("przyjęcie zmienione importem");
        }
        if (!addedCorrections.isEmpty()) {
            parts.add("nowe: " + correctionsLabel(addedCorrections, labels));
        }
        if (!removedCorrections.isEmpty()) {
            parts.add("odpięte: " + correctionsLabel(removedCorrections, labels));
        }
        if (!added && addedCorrections.isEmpty() && removedCorrections.isEmpty() && purchaseDelta.signum() == 0) {
            parts.add("korekta zmieniona importem");
        }
        return String.join("; ", parts);
    }

    /** Opisy korekt przyjęć z bieżącego stanu i zmian (też odpiętych), po id korekty. */
    private Map<UUID, String> correctionLabels(UUID storeId, List<Current> main, List<Change> changes) {
        return correctionLabels(storeId, Stream.concat(
                        main.stream().flatMap(current -> current.corrections().stream()),
                        changes.stream().flatMap(change -> Stream.concat(change.current().corrections().stream(),
                                change.removedCorrections().stream())))
                .toList());
    }

    /**
     * Opisy korekt po id: numer faktury korygującej, bo z nim pracuje księgowość. Najpierw przypięte faktury KSeF
     * (z datą wystawienia), potem numer dokumentu dostawcy z raportu; bez obu (zwykle korekta błędu sklepu) numer
     * wewnętrzny z dopiskiem, że faktury nie ma.
     */
    private Map<UUID, String> correctionLabels(UUID storeId, Collection<Document> corrections) {
        if (corrections.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Document> byId = corrections.stream()
                .collect(Collectors.toMap(Document::getId, Function.identity(), (a, b) -> a));
        Map<UUID, List<LinkedKsefInvoice>> invoices = ksefLinks.invoicesOf(storeId, byId.values());
        return byId.values().stream().collect(Collectors.toMap(Document::getId,
                correction -> correctionLabel(correction, invoices.getOrDefault(correction.getId(), List.of()))));
    }

    private static String correctionLabel(Document correction, List<LinkedKsefInvoice> invoices) {
        if (!invoices.isEmpty()) {
            return invoices.stream()
                    .map(invoice -> invoice.invoiceNumber()
                            + (invoice.issueDate() != null ? " z " + invoice.issueDate().format(DATE) : ""))
                    .distinct().collect(Collectors.joining(", "));
        }
        if (correction.getSupplierDocumentNumber() != null) {
            return correction.getSupplierDocumentNumber();
        }
        return "brak nr faktury (" + correction.getType().singular() + " " + correction.getNumber()
                + " z " + correction.getDocumentDate().format(DATE) + ")";
    }

    private Map<UUID, Document> headers(UUID storeId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return documents.findAllById(ids).stream()
                .filter(document -> document.getStore().getId().equals(storeId))
                .collect(Collectors.toMap(Document::getId, Function.identity()));
    }

    // --- Pomocnicze ---

    private static Set<String> codes(List<DeliveryReportExcludedSupplier> excluded) {
        return excluded.stream().map(DeliveryReportExcludedSupplier::getSupplierCode).collect(Collectors.toSet());
    }

    /** Opis pomijanych dostawców do stopki raportu; {@code null}, gdy nikogo nie pomija. */
    private static String excludedLabel(List<DeliveryReportExcludedSupplier> excluded) {
        return excluded.isEmpty() ? null
                : excluded.stream().map(DeliveryReportExcludedSupplier::label).collect(Collectors.joining("; "));
    }

    private static void footer(List<Row> sheet, String excludedSuppliers, String note) {
        sheet.add(Row.of());
        if (excludedSuppliers != null) {
            sheet.add(Row.of("Pominięci dostawcy: " + excludedSuppliers));
        }
        sheet.add(Row.of(note));
    }

    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("Koniec okresu jest przed jego początkiem");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.badRequest("Raport obejmuje najwyżej " + MAX_DAYS + " dni");
        }
    }

    /** Najpóźniej zamknięte spośród aktywnych. */
    private static Optional<DeliveryReportClosing> latest(List<DeliveryReportClosing> active) {
        return active.stream().max(Comparator.comparing(DeliveryReportClosing::getClosedAt));
    }

    private static String period(DeliveryReportClosing closing) {
        return closing.getPeriodFrom().format(DATE) + " - " + closing.getPeriodTo().format(DATE)
                + " (zamknięty " + DATE_TIME.format(closing.getClosedAt()) + ")";
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }

    private DocumentService.ExportFile file(String name, List<Row> sheet) {
        return new DocumentService.ExportFile(name, XLSX, xlsxWriter.writeRows("Przyjęcia", COLUMNS, sheet));
    }

    private static String deliveriesWord(int count) {
        return polishPlural(count, "przyjęcie", "przyjęcia", "przyjęć");
    }

    private static String changesWord(int count) {
        return polishPlural(count, "zmiana", "zmiany", "zmian");
    }

    private static String polishPlural(int count, String one, String few, String many) {
        if (count == 1) {
            return one;
        }
        int last = count % 10;
        int lastTwo = count % 100;
        return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? few : many;
    }
}
