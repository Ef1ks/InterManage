package com.intermanage.backend.dataentry.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.DataEntryInvoiceItem;
import com.intermanage.backend.dataentry.dto.DataEntryInvoicePage;
import com.intermanage.backend.dataentry.dto.InvoiceBulkRequest;
import com.intermanage.backend.dataentry.dto.InvoiceBulkResult;
import com.intermanage.backend.dataentry.dto.InvoiceBulkUndoRequest;
import com.intermanage.backend.dataentry.dto.SkippedSupplier;
import com.intermanage.backend.dataentry.dto.SkippedSupplierRequest;
import com.intermanage.backend.dataentry.entity.DataEntryInvoiceMark;
import com.intermanage.backend.dataentry.entity.DataEntrySkippedSupplier;
import com.intermanage.backend.dataentry.entity.InvoiceMarkKind;
import com.intermanage.backend.dataentry.entity.SupplierRuleMode;
import com.intermanage.backend.dataentry.mapper.DataEntryInvoiceMapper;
import com.intermanage.backend.dataentry.repository.DataEntryInvoiceMarkRepository;
import com.intermanage.backend.dataentry.repository.DataEntrySkippedSupplierRepository;
import com.intermanage.backend.ksef.component.KsefInvoicePdf;
import com.intermanage.backend.ksef.dto.KsefInvoicePage;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceSearch;
import com.intermanage.backend.ksef.service.KsefInvoiceService;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Okno wczytywania faktur we wprowadzaniu danych, osobne od modułu Faktury KSeF: znaczniki sklepu (wczytana, nie moja;
 * pobrana to tylko informacja), znaczniki innych sklepów firmy jako informacja i reguły dostawców (pomijany: jego
 * faktur nie ma w oknie; odkładany: jego faktury są od razu w "Wczytane"). Docelowo na liście "Do wczytania" nie
 * zostaje żadna faktura.
 */
@Service
public class DataEntryInvoiceService {

    /** Najwięcej faktur w jednym działaniu zbiorczym. */
    public static final int BULK_LIMIT = 5000;

    /** Status na liście: do wczytania, już obsłużone (wczytane, nie moje, od odkładanych dostawców) albo wszystkie. */
    public enum ListStatus {
        NEW, DONE, ALL
    }

    private final KsefInvoiceService ksefInvoices;
    private final DataEntryInvoiceMarkRepository marks;
    private final DataEntrySkippedSupplierRepository skipped;
    private final AppUserRepository users;
    private final DataEntryInvoiceMapper mapper;
    private final KsefConnectionRepository connections;

    public DataEntryInvoiceService(KsefInvoiceService ksefInvoices, DataEntryInvoiceMarkRepository marks,
            DataEntrySkippedSupplierRepository skipped, AppUserRepository users,
            DataEntryInvoiceMapper mapper, KsefConnectionRepository connections) {
        this.ksefInvoices = ksefInvoices;
        this.connections = connections;
        this.marks = marks;
        this.skipped = skipped;
        this.users = users;
        this.mapper = mapper;
    }

    /**
     * Faktury albo korekty widoczne w sklepie (IDWew jak w Fakturach KSeF: {@code internalId} = null to IDWew sklepu
     * i bez IDWew), najnowsze na górze. Dostawcy pomijani w tym oknie albo w Fakturach KSeF są ukryci, dopóki
     * {@code showSkipped} nie pokaże i ich faktur.
     */
    @Transactional(readOnly = true)
    public DataEntryInvoicePage list(UUID storeId, int page, String query, String internalId, boolean corrections,
            ListStatus status, boolean showSkipped, boolean myDeliveries, boolean quantityChanges) {
        KsefInvoicePage found = ksefInvoices.list(storeId, page,
                filter(query, internalId, corrections, showSkipped, myDeliveries, quantityChanges),
                extra(storeId, status, showSkipped), "issueDate", "desc");
        return new DataEntryInvoicePage(items(storeId, found.invoices()), found.page(), found.hasMore(),
                found.matchingCount());
    }

    private static KsefInvoiceSearch.Filter filter(String query, String internalId, boolean corrections,
            boolean showSkipped, boolean myDeliveries, boolean quantityChanges) {
        return new KsefInvoiceSearch.Filter(query, null, null, KsefInvoiceSearch.Entered.ALL,
                corrections ? KsefInvoiceSearch.Kind.CORRECTIONS : KsefInvoiceSearch.Kind.INVOICES,
                internalId == null || internalId.isBlank() || "ALL".equals(internalId) ? null : internalId,
                showSkipped, myDeliveries, quantityChanges);
    }

    /**
     * Do wczytania: bez znacznika "wczytana" / "nie moja" sklepu (pobranie się nie liczy), bez dokumentu z raportu
     * w sklepie firmy (przyjęcie PZ z jej numerem albo przypięty dokument: faktura jest już w systemie sklepu, więc
     * wczytana z automatu) i nie od odkładanego dostawcy; obsłużone: odwrotnie. Faktury pomijanych dostawców tylko
     * z {@code showSkipped}.
     */
    private Specification<KsefInvoice> extra(UUID storeId, ListStatus status, boolean showSkipped) {
        String nip = connections.findById(storeId).map(KsefConnection::getNip).orElse(null);
        return (root, query, cb) -> {
            Predicate handled = cb.or(cb.exists(handledMark(root, query, cb, storeId)),
                    cb.exists(rule(root, query, cb, storeId, SupplierRuleMode.DONE)));
            if (nip != null) {
                handled = cb.or(handled, KsefInvoiceSearch.hasDocument(root, query, cb, nip));
            }
            Predicate all = cb.conjunction();
            if (!showSkipped) {
                all = cb.and(all, cb.not(cb.exists(rule(root, query, cb, storeId, SupplierRuleMode.HIDDEN))));
            }
            if (status == ListStatus.NEW) {
                all = cb.and(all, cb.not(handled));
            } else if (status == ListStatus.DONE) {
                all = cb.and(all, handled);
            }
            return all;
        };
    }

    private static Subquery<Integer> handledMark(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            UUID storeId) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<DataEntryInvoiceMark> mark = subquery.from(DataEntryInvoiceMark.class);
        return subquery.select(cb.literal(1)).where(cb.equal(mark.get("storeId"), storeId),
                cb.equal(mark.get("invoiceId"), root.get("id")), cb.isNull(mark.get("deletedAt")),
                mark.get("kind").in(InvoiceMarkKind.LOADED, InvoiceMarkKind.NOT_MINE));
    }

    private static Subquery<Integer> rule(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            UUID storeId, SupplierRuleMode mode) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<DataEntrySkippedSupplier> supplier = subquery.from(DataEntrySkippedSupplier.class);
        return subquery.select(cb.literal(1)).where(cb.equal(supplier.get("storeId"), storeId),
                cb.equal(supplier.get("sellerNip"), root.get("sellerNip")), cb.isNull(supplier.get("deletedAt")),
                cb.equal(supplier.get("mode"), mode));
    }

    private List<DataEntryInvoiceItem> items(UUID storeId, List<KsefInvoiceSummary> invoices) {
        if (invoices.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DataEntryInvoiceMark>> byInvoice = marks
                .findByInvoiceIdInAndDeletedAtIsNull(invoices.stream().map(KsefInvoiceSummary::id).toList()).stream()
                .collect(Collectors.groupingBy(DataEntryInvoiceMark::getInvoiceId));
        Map<String, SupplierRuleMode> rules = skipped.findByStoreIdAndDeletedAtIsNullOrderBySellerNameAsc(storeId)
                .stream().collect(Collectors.toMap(DataEntrySkippedSupplier::getSellerNip,
                        DataEntrySkippedSupplier::getMode, (a, b) -> a));
        return invoices.stream().map(invoice -> {
            List<DataEntryInvoiceMark> found = byInvoice.getOrDefault(invoice.id(), List.of());
            List<DataEntryInvoiceMark> ownMarks = found.stream()
                    .filter(mark -> mark.getStoreId().equals(storeId)).toList();
            DataEntryInvoiceMark own = ownMarks.stream().filter(mark -> mark.getKind().handled())
                    .max(Comparator.comparing((DataEntryInvoiceMark mark) -> mark.getKind() == InvoiceMarkKind.NOT_MINE))
                    .orElse(null);
            DataEntryInvoiceMark downloaded = ownMarks.stream()
                    .filter(mark -> mark.getKind() == InvoiceMarkKind.DOWNLOADED).findFirst().orElse(null);
            return new DataEntryInvoiceItem(invoice, own != null ? own.getKind() : null,
                    own != null ? own.getCreatedAt() : null, downloaded != null ? downloaded.getCreatedAt() : null,
                    rules.get(invoice.sellerNip()));
        }).toList();
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }

    /** Znacznik sklepu (ten sam rodzaj drugi raz niczego nie dodaje); zwraca fakturę z nowym statusem. */
    @Transactional
    public DataEntryInvoiceItem mark(UUID storeId, UUID invoiceId, InvoiceMarkKind kind, UUID userId) {
        KsefInvoiceSummary invoice = ksefInvoices.summary(storeId, invoiceId);
        addMark(storeId, invoiceId, kind, userId);
        return items(storeId, List.of(invoice)).getFirst();
    }

    private void addMark(UUID storeId, UUID invoiceId, InvoiceMarkKind kind, UUID userId) {
        if (marks.findByStoreIdAndInvoiceIdAndKindAndDeletedAtIsNull(storeId, invoiceId, kind).isEmpty()) {
            marks.saveAndFlush(new DataEntryInvoiceMark(storeId, invoiceId, kind, user(userId)));
        }
    }

    /**
     * Faktura wraca na listę "Do wczytania": znaczniki "wczytana" i "nie moja" sklepu dostają znacznik usunięcia
     * (informacja o pobraniu zostaje).
     */
    @Transactional
    public DataEntryInvoiceItem restore(UUID storeId, UUID invoiceId, UUID userId) {
        KsefInvoiceSummary invoice = ksefInvoices.summary(storeId, invoiceId);
        AppUser user = user(userId);
        marks.findByStoreIdAndInvoiceIdAndDeletedAtIsNull(storeId, invoiceId).stream()
                .filter(mark -> mark.getKind().handled()).forEach(mark -> mark.delete(user));
        marks.flush();
        return items(storeId, List.of(invoice)).getFirst();
    }

    /**
     * Działanie na zaznaczonych fakturach: podanych albo wszystkich pasujących do filtrów listy (najwyżej
     * {@link #BULK_LIMIT}), bez wykluczonych. Zwraca dodane i usunięte znaczniki do „Cofnij”.
     */
    @Transactional
    public InvoiceBulkResult bulk(UUID storeId, InvoiceBulkRequest request, UUID userId) {
        List<UUID> ids;
        if (request.invoiceIds() != null) {
            if (request.invoiceIds().isEmpty()) {
                return new InvoiceBulkResult(0, List.of(), List.of());
            }
            // Tylko faktury widoczne w sklepie.
            KsefInvoiceSearch.Filter any = new KsefInvoiceSearch.Filter(null, null, null,
                    KsefInvoiceSearch.Entered.ALL, KsefInvoiceSearch.Kind.ALL, null, true);
            List<UUID> wanted = request.invoiceIds();
            ids = ksefInvoices.ids(storeId, any, (root, query, cb) -> root.get("id").in(wanted), BULK_LIMIT);
        } else if (request.filter() != null) {
            InvoiceBulkRequest.Filter filter = request.filter();
            ids = ksefInvoices.ids(storeId,
                    filter(filter.query(), filter.internalId(), "CORRECTIONS".equals(filter.kind()),
                            Boolean.TRUE.equals(filter.showSkipped()), Boolean.TRUE.equals(filter.myDeliveries()),
                            Boolean.TRUE.equals(filter.quantityChanges())),
                    extra(storeId, filter.status() != null ? filter.status() : ListStatus.NEW,
                            Boolean.TRUE.equals(filter.showSkipped())),
                    BULK_LIMIT);
        } else {
            throw ApiException.badRequest("Brak zaznaczonych faktur");
        }
        Set<UUID> excluded = request.excludeIds() != null ? new HashSet<>(request.excludeIds()) : Set.of();
        ids = ids.stream().filter(id -> !excluded.contains(id)).toList();
        if (ids.isEmpty()) {
            return new InvoiceBulkResult(0, List.of(), List.of());
        }
        AppUser user = user(userId);
        List<DataEntryInvoiceMark> active = marks.findByStoreIdAndInvoiceIdInAndDeletedAtIsNull(storeId, ids);
        if (request.action() == InvoiceBulkRequest.Action.RESTORE) {
            List<DataEntryInvoiceMark> removed = active.stream().filter(mark -> mark.getKind().handled()).toList();
            removed.forEach(mark -> mark.delete(user));
            marks.flush();
            return new InvoiceBulkResult(
                    (int) removed.stream().map(DataEntryInvoiceMark::getInvoiceId).distinct().count(), List.of(),
                    removed.stream().map(DataEntryInvoiceMark::getId).toList());
        }
        InvoiceMarkKind kind = request.action() == InvoiceBulkRequest.Action.LOADED ? InvoiceMarkKind.LOADED
                : InvoiceMarkKind.NOT_MINE;
        Set<UUID> already = active.stream().filter(mark -> mark.getKind() == kind)
                .map(DataEntryInvoiceMark::getInvoiceId).collect(Collectors.toSet());
        List<DataEntryInvoiceMark> created = new ArrayList<>();
        for (UUID id : ids) {
            if (!already.contains(id)) {
                created.add(new DataEntryInvoiceMark(storeId, id, kind, user));
            }
        }
        marks.saveAllAndFlush(created);
        return new InvoiceBulkResult(created.size(), created.stream().map(DataEntryInvoiceMark::getId).toList(),
                List.of());
    }

    /** „Cofnij” działania zbiorczego: dodane znaczniki usunięte (znacznik), usunięte dodane od nowa. */
    @Transactional
    public void undoBulk(UUID storeId, InvoiceBulkUndoRequest request, UUID userId) {
        AppUser user = user(userId);
        if (request.createdMarkIds() != null && !request.createdMarkIds().isEmpty()) {
            marks.findByStoreIdAndIdIn(storeId, request.createdMarkIds()).forEach(mark -> mark.delete(user));
        }
        if (request.deletedMarkIds() != null && !request.deletedMarkIds().isEmpty()) {
            for (DataEntryInvoiceMark mark : marks.findByStoreIdAndIdIn(storeId, request.deletedMarkIds())) {
                marks.flush();
                addMark(storeId, mark.getInvoiceId(), mark.getKind(), userId);
            }
        }
        marks.flush();
    }

    /**
     * PDF faktury (podstawowy do wprowadzania albo pełny); pobranie i wydruk zapisują informację "pobrana", ale faktura
     * dalej czeka na wczytanie.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public KsefInvoiceService.ExportFile pdf(UUID storeId, UUID invoiceId, KsefInvoicePdf.Variant variant,
            UUID userId) {
        KsefInvoiceService.ExportFile file = ksefInvoices.pdf(storeId, invoiceId, variant);
        addMark(storeId, invoiceId, InvoiceMarkKind.DOWNLOADED, userId);
        return file;
    }

    // ---------- reguły dostawców: pomijani i odkładani do wczytanych

    @Transactional(readOnly = true)
    public List<SkippedSupplier> skippedSuppliers(UUID storeId) {
        return skipped.findByStoreIdAndDeletedAtIsNullOrderBySellerNameAsc(storeId).stream().map(mapper::toResponse)
                .toList();
    }

    /** Reguła dostawcy; inna reguła tego NIP-u dostaje znacznik usunięcia (jedna aktywna na dostawcę). */
    @Transactional
    public SkippedSupplier skip(UUID storeId, SkippedSupplierRequest request, UUID userId) {
        String nip = request.sellerNip().strip();
        SupplierRuleMode mode = request.mode() != null ? request.mode() : SupplierRuleMode.HIDDEN;
        DataEntrySkippedSupplier active = skipped.findByStoreIdAndSellerNipAndDeletedAtIsNull(storeId, nip)
                .orElse(null);
        if (active != null && active.getMode() == mode) {
            return mapper.toResponse(active);
        }
        if (active != null) {
            active.delete(user(userId));
            skipped.flush();
        }
        String name = request.sellerName() != null && !request.sellerName().isBlank() ? request.sellerName().strip()
                : active != null ? active.getSellerName() : null;
        return mapper.toResponse(
                skipped.saveAndFlush(new DataEntrySkippedSupplier(storeId, nip, name, mode, user(userId))));
    }

    @Transactional
    public void unskip(UUID storeId, UUID id, UUID userId) {
        DataEntrySkippedSupplier supplier = skipped.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
        if (!supplier.isDeleted()) {
            supplier.delete(user(userId));
        }
    }

    /** Przywrócenie reguły; gdy ten NIP ma już inną aktywną regułę, zostaje tamta. */
    @Transactional
    public SkippedSupplier restoreSkipped(UUID storeId, UUID id) {
        DataEntrySkippedSupplier supplier = skipped.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
        if (supplier.isDeleted()) {
            DataEntrySkippedSupplier active = skipped
                    .findByStoreIdAndSellerNipAndDeletedAtIsNull(storeId, supplier.getSellerNip()).orElse(null);
            if (active != null) {
                return mapper.toResponse(active);
            }
            supplier.restore();
        }
        return mapper.toResponse(supplier);
    }
}
