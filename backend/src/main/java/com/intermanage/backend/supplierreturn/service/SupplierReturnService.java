package com.intermanage.backend.supplierreturn.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.collector.dto.CollectorListDetails;
import com.intermanage.backend.collector.entity.CollectorListOutput;
import com.intermanage.backend.collector.service.CollectorListService;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;
import com.intermanage.backend.supplierreturn.dto.SupplierReturnDtos;
import com.intermanage.backend.supplierreturn.entity.SupplierReturn;
import com.intermanage.backend.supplierreturn.entity.SupplierReturnEvent;
import com.intermanage.backend.supplierreturn.entity.SupplierReturnLine;
import com.intermanage.backend.supplierreturn.entity.SupplierReturnStatus;
import com.intermanage.backend.supplierreturn.repository.SupplierReturnEventRepository;
import com.intermanage.backend.supplierreturn.repository.SupplierReturnLineRepository;
import com.intermanage.backend.supplierreturn.repository.SupplierReturnRepository;

/**
 * Zwroty do dostawcy (Zakupy). Lista powstaje z list kolektora (protokół „Zwrot do dostawcy”) albo z tabeli Protokołów,
 * a potem prowadzi się ją statusami i dokumentami: bon dostawy, z którą towar przyjechał (PZ), bon korekty z raportu
 * dokumentów (WZ albo PK) i korekta z KSeF. Korekty aplikacja próbuje podpiąć sama po artykułach (ITM8, EAN): gdy jedna
 * korekta dostawcy po dniu zwrotu ma co najmniej połowę artykułów zwrotu i żadna inna nie ma ich tyle samo. Podpięta
 * korekta rozlicza otwarty zwrot. Wszystko zapisuje historia zwrotu.
 */
@Service
public class SupplierReturnService {

    private static final int LIST_LIMIT = 300;
    /** Otwartych zwrotów sprawdzanych przy każdym wejściu na listę (korekty przychodzą z importami dokumentów i KSeF). */
    private static final int AUTO_MATCH_LIMIT = 30;
    private static final Duration CORRECTION_BEFORE = Duration.ofDays(14);
    private static final Duration DELIVERY_BEFORE = Duration.ofDays(365);
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SupplierReturnRepository returns;
    private final SupplierReturnLineRepository lines;
    private final SupplierReturnEventRepository events;
    private final CollectorListService collectorLists;
    private final DocumentRepository documents;
    private final KsefInvoiceRepository invoices;
    private final KsefInvoiceReader reader;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public SupplierReturnService(SupplierReturnRepository returns, SupplierReturnLineRepository lines,
            SupplierReturnEventRepository events, CollectorListService collectorLists, DocumentRepository documents,
            KsefInvoiceRepository invoices, KsefInvoiceReader reader, SupplierRepository suppliers,
            StoreRepository stores, AppUserRepository users) {
        this.returns = returns;
        this.lines = lines;
        this.events = events;
        this.collectorLists = collectorLists;
        this.documents = documents;
        this.invoices = invoices;
        this.reader = reader;
        this.suppliers = suppliers;
        this.stores = stores;
        this.users = users;
    }

    // ---------------------------------------------------------------- lista

    /** Zwroty sklepu od najnowszego; otwarte bez korekty najpierw próbują podpiąć ją same. */
    @Transactional
    public List<SupplierReturnDtos.Summary> list(UUID storeId) {
        List<SupplierReturn> found = returns.findLatest(storeId, Limit.of(LIST_LIMIT));
        Map<UUID, List<SupplierReturnLine>> linesOf = linesOf(found);
        found.stream().filter(this::needsCorrection).limit(AUTO_MATCH_LIMIT)
                .forEach(item -> autoMatch(item, linesOf.getOrDefault(item.getId(), List.of())));
        return found.stream().map(item -> summary(item, linesOf.getOrDefault(item.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public long countOpen(UUID storeId) {
        return returns.countOpen(storeId);
    }

    @Transactional
    public SupplierReturnDtos.Details details(UUID storeId, UUID id) {
        SupplierReturn item = find(storeId, id);
        List<SupplierReturnLine> found = returns.findLines(List.of(id));
        if (needsCorrection(item)) {
            autoMatch(item, found);
        }
        return details(item, found);
    }

    // ---------------------------------------------------------------- tworzenie

    @Transactional
    public SupplierReturnDtos.Details fromCollector(UUID storeId, SupplierReturnDtos.FromCollectorRequest request,
            UUID actorId) {
        CollectorListDetails details = collectorLists.details(storeId, request.listIds());
        List<SupplierReturnDtos.Line> items = details.rows().stream().filter(row -> row.quantity().signum() > 0)
                .map(row -> new SupplierReturnDtos.Line(row.itm8(), row.ean(), row.name(), row.brand(), row.quantity()))
                .toList();
        SupplierReturnDtos.Details created = create(storeId, SupplierReturn.Source.COLLECTOR, items, null,
                request.note(), actorId);
        collectorLists.markSaved(storeId, CollectorListOutput.SUPPLIER_RETURN, request.listIds(),
                "Zwrot do dostawcy", actorId);
        return created;
    }

    @Transactional
    public SupplierReturnDtos.Details fromProtocols(UUID storeId, SupplierReturnDtos.FromProtocolsRequest request,
            UUID actorId) {
        return create(storeId, SupplierReturn.Source.PROTOCOLS, request.lines(), request.supplierId(), request.note(),
                actorId);
    }

    private SupplierReturnDtos.Details create(UUID storeId, SupplierReturn.Source source,
            List<SupplierReturnDtos.Line> items, UUID supplierId, String note, UUID actorId) {
        List<SupplierReturnDtos.Line> usable = items.stream().filter(line -> line.quantity().signum() > 0).toList();
        if (usable.isEmpty()) {
            throw ApiException.badRequest("Zwrot nie ma artykułów z ilością");
        }
        Supplier supplier = supplierId != null ? supplier(storeId, supplierId) : suggestSupplier(storeId, usable);
        AppUser actor = users.getReferenceById(actorId);
        SupplierReturn item = returns.saveAndFlush(new SupplierReturn(storeId, source, supplier, blank(note), actor));
        int position = 1;
        List<SupplierReturnLine> saved = new ArrayList<>();
        for (SupplierReturnDtos.Line line : usable) {
            saved.add(lines.save(new SupplierReturnLine(item.getId(), position++, blank(line.itm8()),
                    blank(line.ean()), cut(line.name(), 200), line.brand() == null ? null : cut(line.brand(), 100),
                    line.quantity())));
        }
        event(item, "CREATED", (source == SupplierReturn.Source.COLLECTOR ? "Z kolektora" : "Z Protokołów")
                + ", artykułów: " + saved.size()
                + (supplier != null && supplierId == null ? ", dostawca z ostatnich dostaw: " + supplier.getName() : ""),
                actor);
        autoMatch(item, saved);
        return details(item, saved);
    }

    /** Dostawca, od którego ostatnio przyjechała większość artykułów zwrotu (z przyjęć PZ). */
    private Supplier suggestSupplier(UUID storeId, List<SupplierReturnDtos.Line> items) {
        List<String> itm8s = items.stream().map(SupplierReturnDtos.Line::itm8).filter(itm8 -> itm8 != null
                && !itm8.isBlank()).distinct().toList();
        if (itm8s.isEmpty()) {
            return null;
        }
        return returns.findMostCommonLastSupplier(storeId, itm8s).stream().findFirst()
                .flatMap(code -> returns.findSupplierByCode(storeId, code).stream().findFirst())
                .flatMap(id -> suppliers.findInStore(storeId, id)).orElse(null);
    }

    // ---------------------------------------------------------------- zmiany

    @Transactional
    public SupplierReturnDtos.Details setStatus(UUID storeId, UUID id, SupplierReturnDtos.StatusRequest request,
            UUID actorId) {
        SupplierReturn item = find(storeId, id);
        if (item.getStatus() != request.status()) {
            String comment = blank(request.comment());
            event(item, "STATUS", item.getStatus().label() + " → " + request.status().label()
                    + (comment == null ? "" : ": " + comment), users.getReferenceById(actorId));
            item.setStatus(request.status());
        }
        return details(item, returns.findLines(List.of(id)));
    }

    @Transactional
    public SupplierReturnDtos.Details setSupplier(UUID storeId, UUID id, SupplierReturnDtos.SupplierRequest request,
            UUID actorId) {
        SupplierReturn item = find(storeId, id);
        Supplier supplier = request.supplierId() == null ? null : supplier(storeId, request.supplierId());
        item.setSupplier(supplier);
        event(item, "SUPPLIER", supplier == null ? "Bez dostawcy" : "Dostawca: " + supplier.getName(),
                users.getReferenceById(actorId));
        return details(item, returns.findLines(List.of(id)));
    }

    @Transactional
    public SupplierReturnDtos.Details setNote(UUID storeId, UUID id, SupplierReturnDtos.NoteRequest request) {
        SupplierReturn item = find(storeId, id);
        item.setNote(blank(request.note()));
        return details(item, returns.findLines(List.of(id)));
    }

    /** Bon dostawy, z którą towar przyjechał; bez dostawcy zwrot bierze dostawcę z bonu. */
    @Transactional
    public SupplierReturnDtos.Details setDelivery(UUID storeId, UUID id, SupplierReturnDtos.AttachRequest request,
            UUID actorId) {
        SupplierReturn item = find(storeId, id);
        AppUser actor = users.getReferenceById(actorId);
        if (request.id() == null) {
            if (item.getDeliveryDocument() != null) {
                event(item, "DELIVERY_REMOVED", "Odpięto bon " + bon(item.getDeliveryDocument()), actor);
                item.setDeliveryDocument(null);
            }
        } else {
            Document document = document(storeId, request.id(), Set.of(DocumentType.PZ));
            item.setDeliveryDocument(document);
            event(item, "DELIVERY", "Bon dostawy " + bon(document), actor);
            if (item.getSupplier() == null) {
                supplierOf(storeId, document).ifPresent(supplier -> {
                    item.setSupplier(supplier);
                    event(item, "SUPPLIER", "Dostawca z bonu: " + supplier.getName(), null);
                });
            }
        }
        return details(item, returns.findLines(List.of(id)));
    }

    @Transactional
    public SupplierReturnDtos.Details setCorrection(UUID storeId, UUID id, SupplierReturnDtos.AttachRequest request,
            UUID actorId) {
        SupplierReturn item = find(storeId, id);
        AppUser actor = users.getReferenceById(actorId);
        if (request.id() == null) {
            if (item.getCorrectionDocument() != null) {
                event(item, "CORRECTION_REMOVED", "Odpięto bon korekty " + bon(item.getCorrectionDocument()), actor);
                item.setCorrectionDocument(null);
            }
        } else {
            if (returns.findTakenCorrectionDocuments(storeId, id).contains(request.id())) {
                throw ApiException.conflict("Ten bon korekty jest już przy innym zwrocie");
            }
            Document document = document(storeId, request.id(), Set.of(DocumentType.WZ, DocumentType.PK));
            item.setCorrectionDocument(document);
            event(item, "CORRECTION", "Bon korekty " + bon(document), actor);
            settle(item, actor);
        }
        return details(item, returns.findLines(List.of(id)));
    }

    @Transactional
    public SupplierReturnDtos.Details setInvoice(UUID storeId, UUID id, SupplierReturnDtos.AttachRequest request,
            UUID actorId) {
        SupplierReturn item = find(storeId, id);
        AppUser actor = users.getReferenceById(actorId);
        if (request.id() == null) {
            if (item.getKsefInvoice() != null) {
                event(item, "INVOICE_REMOVED", "Odpięto korektę " + item.getKsefInvoice().getInvoiceNumber(), actor);
                item.setKsefInvoice(null);
            }
        } else {
            if (returns.findTakenInvoices(storeId, id).contains(request.id())) {
                throw ApiException.conflict("Ta korekta jest już przy innym zwrocie");
            }
            Store store = store(storeId);
            KsefInvoice invoice = invoices.findById(request.id())
                    .filter(found -> found.isCorrection() && store.getNip() != null
                            && store.getNip().equals(found.getNip()))
                    .orElseThrow(() -> ApiException.notFound("Nie znaleziono korekty z KSeF"));
            item.setKsefInvoice(invoice);
            event(item, "INVOICE", "Korekta z KSeF " + invoice.getInvoiceNumber(), actor);
            settle(item, actor);
        }
        return details(item, returns.findLines(List.of(id)));
    }

    @Transactional
    public void remove(UUID storeId, UUID id, UUID actorId) {
        SupplierReturn item = find(storeId, id);
        item.markDeleted(users.getReferenceById(actorId));
    }

    @Transactional
    public void restore(UUID storeId, UUID id) {
        returns.findById(id).filter(found -> found.getStoreId().equals(storeId) && found.getDeletedAt() != null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zwrotu"))
                .restore();
    }

    // ---------------------------------------------------------------- kandydaci i automat

    @Transactional(readOnly = true)
    public SupplierReturnDtos.Candidates candidates(UUID storeId, UUID id) {
        SupplierReturn item = find(storeId, id);
        List<SupplierReturnLine> found = returns.findLines(List.of(id));
        Codes codes = codes(found);
        String supplierCode = item.getSupplier() == null ? null : item.getSupplier().getCode();
        LocalDateTime created = LocalDateTime.ofInstant(item.getCreatedAt(), ZONE);
        List<SupplierReturnDtos.DocumentCandidate> deliveries = returns.findDocumentCandidates(storeId,
                List.of(DocumentType.PZ.name()), created.minus(DELIVERY_BEFORE), supplierCode, codes.itm8s(),
                codes.eans()).stream().map(SupplierReturnService::candidate).toList();
        Set<UUID> taken = new HashSet<>(returns.findTakenCorrectionDocuments(storeId, id));
        List<SupplierReturnDtos.DocumentCandidate> corrections = correctionCandidates(storeId, item, codes).stream()
                .filter(candidate -> !taken.contains(candidate.getId()))
                .map(SupplierReturnService::candidate).toList();
        Set<UUID> takenInvoices = new HashSet<>(returns.findTakenInvoices(storeId, id));
        List<SupplierReturnDtos.InvoiceCandidate> invoiceList = invoiceCandidates(storeId, item).stream()
                .filter(candidate -> !takenInvoices.contains(candidate.getId()))
                .map(candidate -> new SupplierReturnDtos.InvoiceCandidate(new SupplierReturnDtos.InvoiceRef(
                        candidate.getId(), candidate.getInvoiceNumber(), candidate.getIssueDate(),
                        candidate.getNetAmount()), common(candidate.getXml(), codes)))
                .sorted((a, b) -> Long.compare(b.common() == null ? -1 : b.common(),
                        a.common() == null ? -1 : a.common()))
                .toList();
        return new SupplierReturnDtos.Candidates(found.size(), deliveries, corrections, invoiceList);
    }

    private boolean needsCorrection(SupplierReturn item) {
        return item.getStatus().open() && item.getSupplier() != null
                && (item.getCorrectionDocument() == null || item.getKsefInvoice() == null);
    }

    /**
     * Automatyczne podpięcie korekty: bon korekty dostawcy i korekta z KSeF, każda tylko gdy jedna wyraźnie najlepsza
     * ma co najmniej połowę artykułów zwrotu.
     */
    private void autoMatch(SupplierReturn item, List<SupplierReturnLine> found) {
        if (item.getSupplier() == null || found.isEmpty()) {
            return;
        }
        Codes codes = codes(found);
        long needed = Math.max(1, (found.size() + 1) / 2);
        boolean attached = false;
        if (item.getCorrectionDocument() == null) {
            Set<UUID> taken = new HashSet<>(returns.findTakenCorrectionDocuments(item.getStoreId(), item.getId()));
            List<SupplierReturnRepository.DocumentCandidate> candidates = correctionCandidates(item.getStoreId(), item,
                    codes).stream().filter(candidate -> !taken.contains(candidate.getId())).toList();
            if (!candidates.isEmpty() && candidates.getFirst().getCommon() >= needed
                    && (candidates.size() == 1 || candidates.get(1).getCommon() < candidates.getFirst().getCommon())) {
                Document document = documents.findById(candidates.getFirst().getId()).orElseThrow();
                item.setCorrectionDocument(document);
                event(item, "CORRECTION", "Bon korekty " + bon(document) + " podpięty automatycznie (wspólnych "
                        + "artykułów: " + candidates.getFirst().getCommon() + " z " + found.size() + ")", null);
                attached = true;
            }
        }
        if (item.getKsefInvoice() == null) {
            Set<UUID> taken = new HashSet<>(returns.findTakenInvoices(item.getStoreId(), item.getId()));
            List<Scored> scored = invoiceCandidates(item.getStoreId(), item).stream()
                    .filter(candidate -> !taken.contains(candidate.getId()) && candidate.getXml() != null)
                    .map(candidate -> new Scored(candidate.getId(), common(candidate.getXml(), codes)))
                    .filter(score -> score.common() != null)
                    .sorted((a, b) -> Long.compare(b.common(), a.common())).toList();
            if (!scored.isEmpty() && scored.getFirst().common() >= needed
                    && (scored.size() == 1 || scored.get(1).common() < scored.getFirst().common())) {
                KsefInvoice invoice = invoices.findById(scored.getFirst().id()).orElseThrow();
                item.setKsefInvoice(invoice);
                event(item, "INVOICE", "Korekta z KSeF " + invoice.getInvoiceNumber()
                        + " podpięta automatycznie (wspólnych artykułów: " + scored.getFirst().common() + " z "
                        + found.size() + ")", null);
                attached = true;
            }
        }
        if (attached) {
            settle(item, null);
        }
    }

    private record Scored(UUID id, Long common) {
    }

    /** Podpięta korekta rozlicza otwarty zwrot. */
    private void settle(SupplierReturn item, AppUser actor) {
        if (item.getStatus().open()) {
            event(item, "STATUS", item.getStatus().label() + " → " + SupplierReturnStatus.CORRECTED.label()
                    + " (podpięta korekta)", actor);
            item.setStatus(SupplierReturnStatus.CORRECTED);
        }
    }

    private List<SupplierReturnRepository.DocumentCandidate> correctionCandidates(UUID storeId, SupplierReturn item,
            Codes codes) {
        String supplierCode = item.getSupplier() == null ? null : item.getSupplier().getCode();
        if (supplierCode == null) {
            return List.of();
        }
        LocalDateTime created = LocalDateTime.ofInstant(item.getCreatedAt(), ZONE);
        return returns.findDocumentCandidates(storeId, List.of(DocumentType.WZ.name(), DocumentType.PK.name()),
                created.minus(CORRECTION_BEFORE), supplierCode, codes.itm8s(), codes.eans());
    }

    private List<SupplierReturnRepository.InvoiceCandidate> invoiceCandidates(UUID storeId, SupplierReturn item) {
        Store store = store(storeId);
        String sellerNip = item.getSupplier() == null ? null : item.getSupplier().getNip();
        if (store.getNip() == null || sellerNip == null || sellerNip.isBlank()) {
            return List.of();
        }
        LocalDate since = LocalDate.ofInstant(item.getCreatedAt(), ZONE).minusDays(CORRECTION_BEFORE.toDays());
        return returns.findInvoiceCandidates(store.getNip(), sellerNip.replaceAll("\\D", ""), since);
    }

    /** Ile artykułów zwrotu jest na korekcie z KSeF (GTIN albo indeks pozycji = EAN bez zer wiodących). */
    private Long common(byte[] xml, Codes codes) {
        if (xml == null) {
            return null;
        }
        try {
            Set<String> onInvoice = new HashSet<>();
            for (KsefInvoiceReader.LineCodes line : reader.lineCodes(xml)) {
                addCode(onInvoice, line.gtin());
                addCode(onInvoice, line.index());
            }
            return codes.eans().stream().filter(onInvoice::contains).count();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void addCode(Set<String> codes, String value) {
        if (value != null && value.strip().matches("\\d{6,14}")) {
            codes.add(value.strip().replaceFirst("^0+", ""));
        }
    }

    private record Codes(List<String> itm8s, List<String> eans) {
    }

    /** Kody zwrotu do zapytań; pusta lista dostaje kod, którego nie ma, bo {@code in ()} nie przejdzie. */
    private static Codes codes(Collection<SupplierReturnLine> found) {
        List<String> itm8s = found.stream().map(SupplierReturnLine::getItm8).filter(itm8 -> itm8 != null)
                .distinct().collect(Collectors.toCollection(ArrayList::new));
        List<String> eans = found.stream().map(SupplierReturnLine::getEan).filter(ean -> ean != null && !ean.isBlank())
                .map(ean -> ean.replaceFirst("^0+", "")).distinct().collect(Collectors.toCollection(ArrayList::new));
        if (itm8s.isEmpty()) {
            itm8s.add("-");
        }
        if (eans.isEmpty()) {
            eans.add("-");
        }
        return new Codes(itm8s, eans);
    }

    // ---------------------------------------------------------------- pomocnicze

    private java.util.Optional<Supplier> supplierOf(UUID storeId, Document document) {
        if (document.getSupplierCode() == null) {
            return java.util.Optional.empty();
        }
        return returns.findSupplierByCode(storeId, document.getSupplierCode().replaceFirst("^0+", "")).stream()
                .findFirst().flatMap(id -> suppliers.findInStore(storeId, id));
    }

    private SupplierReturnDtos.Details details(SupplierReturn item, List<SupplierReturnLine> found) {
        CollectorListDetails items = collectorLists.detailsOfItems(item.getStoreId(), found.stream()
                .map(line -> new CollectorListService.Item(line.getItm8(), line.getEan(), line.getName(),
                        line.getBrand(), line.getQuantity()))
                .toList());
        List<SupplierReturnDtos.Event> history = returns.findEvents(item.getId()).stream()
                .map(event -> new SupplierReturnDtos.Event(event.getKind(), event.getDetail(), event.getCreatedAt(),
                        event.getCreatedBy() == null ? null : event.getCreatedBy().getDisplayName(),
                        event.getCreatedBy() == null))
                .toList();
        return new SupplierReturnDtos.Details(summary(item, found), items, history);
    }

    private SupplierReturnDtos.Summary summary(SupplierReturn item, List<SupplierReturnLine> found) {
        Supplier supplier = item.getSupplier();
        KsefInvoice invoice = item.getKsefInvoice();
        return new SupplierReturnDtos.Summary(item.getId(), item.getStatus(), item.getSource(),
                supplier == null ? null : new SupplierReturnDtos.SupplierRef(supplier.getId(), supplier.getCode(),
                        supplier.getName(), supplier.getNip()),
                item.getNote(), item.getCreatedAt(),
                item.getCreatedBy() == null ? null : item.getCreatedBy().getDisplayName(), item.getUpdatedAt(),
                found.size(), found.stream().map(SupplierReturnLine::getQuantity).reduce(BigDecimal.ZERO,
                        BigDecimal::add).stripTrailingZeros(),
                ref(item.getDeliveryDocument()), ref(item.getCorrectionDocument()),
                invoice == null ? null : new SupplierReturnDtos.InvoiceRef(invoice.getId(),
                        invoice.getInvoiceNumber(), invoice.getIssueDate(), invoice.getNetAmount()));
    }

    private static SupplierReturnDtos.DocumentRef ref(Document document) {
        return document == null ? null : new SupplierReturnDtos.DocumentRef(document.getId(), document.getType(),
                document.getNumber(), document.bonNumber(), document.getDocumentDate(),
                document.getSupplierDocumentNumber(), document.getPurchaseValue());
    }

    private static SupplierReturnDtos.DocumentCandidate candidate(SupplierReturnRepository.DocumentCandidate found) {
        DocumentType type = DocumentType.valueOf(found.getType());
        String number = found.getNumber();
        String bon = type == DocumentType.PZ && number.endsWith("00") && number.length() > 2
                ? number.substring(0, number.length() - 2) : number;
        return new SupplierReturnDtos.DocumentCandidate(new SupplierReturnDtos.DocumentRef(found.getId(), type,
                number, bon, found.getDocumentDate(), found.getSupplierDocumentNumber(), found.getPurchaseValue()),
                found.getCommon());
    }

    private Map<UUID, List<SupplierReturnLine>> linesOf(List<SupplierReturn> found) {
        if (found.isEmpty()) {
            return Map.of();
        }
        return returns.findLines(found.stream().map(SupplierReturn::getId).toList()).stream()
                .collect(Collectors.groupingBy(SupplierReturnLine::getReturnId));
    }

    private void event(SupplierReturn item, String kind, String detail, AppUser actor) {
        events.save(new SupplierReturnEvent(item.getId(), kind, detail, actor));
    }

    private Document document(UUID storeId, UUID id, Set<DocumentType> types) {
        return documents.findById(id)
                .filter(found -> found.getStore().getId().equals(storeId) && types.contains(found.getType()))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    private static String bon(Document document) {
        String date = document.getDocumentDate() == null ? "" : " z " + DAY.format(document.getDocumentDate());
        return (document.getType() == DocumentType.PZ ? document.bonNumber() : document.getNumber()) + date;
    }

    private Supplier supplier(UUID storeId, UUID id) {
        return suppliers.findInStore(storeId, id).orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }

    private SupplierReturn find(UUID storeId, UUID id) {
        return returns.findById(id).filter(found -> found.getStoreId().equals(storeId) && found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zwrotu"));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String cut(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }

    /** Wydruk zwrotu i Excel: ten sam podgląd co listy z kolektora. */
    @Transactional(readOnly = true)
    public byte[] pdf(UUID storeId, UUID id, String printedBy) {
        SupplierReturn item = find(storeId, id);
        List<SupplierReturnLine> found = returns.findLines(List.of(id));
        SupplierReturnDtos.Details details = details(item, found);
        String supplier = item.getSupplier() == null ? "dostawca nieustalony"
                : "dostawca: " + item.getSupplier().getName()
                        + (item.getSupplier().getCode() == null ? "" : " (" + item.getSupplier().getCode() + ")");
        String delivery = item.getDeliveryDocument() == null ? null : "Bon dostawy " + bon(item.getDeliveryDocument());
        return collectorLists.pdfOf(storeId, "Zwrot do dostawcy",
                supplier + " · sporządzono " + made(item),
                (delivery == null ? "" : delivery + " · ") + "Stan: " + item.getStatus().label()
                        + (item.getNote() == null ? "" : " · " + item.getNote()),
                details.items(), printedBy, List.of("Wydał ze sklepu", "Odebrał za dostawcę"));
    }

    /** „27.11.2026 09:41, Anna Nowak” do wydruku i Excela. */
    private static String made(SupplierReturn item) {
        return MADE.format(item.getCreatedAt().atZone(ZONE))
                + (item.getCreatedBy() == null ? "" : ", " + item.getCreatedBy().getDisplayName());
    }

    private static final DateTimeFormatter MADE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Transactional(readOnly = true)
    public byte[] xlsx(UUID storeId, UUID id) {
        SupplierReturn item = find(storeId, id);
        List<XlsxWriter.Row> intro = new ArrayList<>();
        intro.add(XlsxWriter.Row.of(XlsxWriter.Cell.bold("Zwrot do dostawcy")));
        intro.add(XlsxWriter.Row.of("Dostawca", item.getSupplier() == null ? "nieustalony"
                : item.getSupplier().getName() + (item.getSupplier().getCode() == null ? ""
                        : " (" + item.getSupplier().getCode() + ")")));
        intro.add(XlsxWriter.Row.of("Sporządzono", made(item)));
        intro.add(XlsxWriter.Row.of("Stan", item.getStatus().label()));
        if (item.getDeliveryDocument() != null) {
            intro.add(XlsxWriter.Row.of("Bon dostawy", bon(item.getDeliveryDocument())));
        }
        if (item.getNote() != null) {
            intro.add(XlsxWriter.Row.of("Notatka", item.getNote()));
        }
        return collectorLists.xlsxOf("Zwrot do dostawcy", intro, details(item, returns.findLines(List.of(id))).items());
    }
}
