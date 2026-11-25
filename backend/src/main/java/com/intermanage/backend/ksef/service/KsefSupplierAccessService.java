package com.intermanage.backend.ksef.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.ksef.dto.KsefInvoicePage;
import com.intermanage.backend.ksef.dto.KsefSupplierAccessResponse;
import com.intermanage.backend.ksef.entity.KsefAllSuppliersAccess;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.repository.KsefAllSuppliersAccessRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceSearch;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.repository.SupplierRepository;
import com.intermanage.backend.task.repository.StoreTaskRepository;

/**
 * Kto w Fakturach KSeF widzi faktury wszystkich dostawców. Domyślnie konto widzi tylko dostawców z listy dostawców
 * sklepu (wgranej z raportu); wszystkich widzą właściciel, Aderon i ranga Księgowość, a pozostałym kontom włącza to
 * Aderon (albo właściciel) osobno w każdym sklepie. Dotyczy listy Faktur KSeF i okna wczytywania faktur we
 * wprowadzaniu danych. Faktury dostawcy spoza listy takie konto znajdzie, wpisując jego NIP: najwyżej
 * {@value #NIP_LOOKUP_LIMIT} najnowsze z ostatniego miesiąca.
 */
@Service
public class KsefSupplierAccessService {

    /** Klucz rangi Księgowość ({@code StoreRoleDefaults}). */
    public static final String ACCOUNTING_RANK = "ACCOUNTING";

    /** Ile faktur dostawcy spoza listy pokazuje wpisany NIP. */
    public static final int NIP_LOOKUP_LIMIT = 3;

    private final KsefAllSuppliersAccessRepository access;
    private final AppUserRepository users;
    private final StoreRepository stores;
    private final StoreTaskRepository tasks;
    private final KsefInvoiceService invoices;
    private final SupplierRepository suppliers;

    public KsefSupplierAccessService(KsefAllSuppliersAccessRepository access, AppUserRepository users,
            StoreRepository stores, StoreTaskRepository tasks, KsefInvoiceService invoices,
            SupplierRepository suppliers) {
        this.invoices = invoices;
        this.suppliers = suppliers;
        this.access = access;
        this.users = users;
        this.stores = stores;
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public boolean seesAllSuppliers(UUID storeId, UUID userId) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        return reason(user, storeId, false) != null || access.findActive(storeId, userId).isPresent();
    }

    /** Lista faktur, jaką widzi konto: bez wglądu we wszystkich dostawców tylko dostawcy z listy albo wpisany NIP. */
    @Transactional(readOnly = true)
    public KsefInvoicePage list(UUID viewerId, UUID storeId, int page, KsefInvoiceSearch.Filter filter,
            Specification<KsefInvoice> extra, String sort, String direction) {
        if (seesAllSuppliers(storeId, viewerId)) {
            return invoices.list(storeId, page, filter, extra, sort, direction);
        }
        String nip = unlistedNip(storeId, filter.query());
        if (nip == null) {
            return invoices.list(storeId, page, filter, and(KsefInvoiceSearch.listedSuppliersOnly(storeId), extra),
                    sort, direction).restricted(false);
        }
        List<UUID> found = page > 0 ? List.of() : recentOfSeller(storeId, nip, filter, extra);
        Specification<KsefInvoice> only = (root, query, cb) -> found.isEmpty() ? cb.disjunction()
                : root.get("id").in(found);
        return invoices.list(storeId, 0, filter.withQuery(null), only, sort, direction).restricted(true);
    }

    /** Id faktur pasujących do filtrów z tym samym ograniczeniem co {@link #list} (np. „zaznacz wszystkie”). */
    @Transactional(readOnly = true)
    public List<UUID> ids(UUID viewerId, UUID storeId, KsefInvoiceSearch.Filter filter,
            Specification<KsefInvoice> extra, int limit) {
        if (seesAllSuppliers(storeId, viewerId)) {
            return invoices.ids(storeId, filter, extra, limit);
        }
        String nip = unlistedNip(storeId, filter.query());
        if (nip == null) {
            return invoices.ids(storeId, filter, and(KsefInvoiceSearch.listedSuppliersOnly(storeId), extra), limit);
        }
        return recentOfSeller(storeId, nip, filter, extra);
    }

    private List<UUID> recentOfSeller(UUID storeId, String nip, KsefInvoiceSearch.Filter filter,
            Specification<KsefInvoice> extra) {
        LocalDate since = LocalDate.now(StoreTime.ZONE).minusMonths(1);
        Specification<KsefInvoice> seller = (root, query, cb) -> cb.and(cb.equal(root.get("sellerNip"), nip),
                cb.greaterThanOrEqualTo(root.get("issueDate"), since));
        return invoices.ids(storeId, filter.withQuery(null), and(seller, extra), NIP_LOOKUP_LIMIT);
    }

    /** NIP wpisany w wyszukiwarkę (z kreskami, spacjami albo „PL”), gdy dostawcy nie ma na liście dostawców sklepu. */
    private String unlistedNip(UUID storeId, String query) {
        if (query == null) {
            return null;
        }
        String digits = query.replaceAll("(?i)^\\s*PL", "").replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{10}")) {
            return null;
        }
        return suppliers.findCodesByNips(storeId, List.of(digits)).isEmpty() ? digits : null;
    }

    private static Specification<KsefInvoice> and(Specification<KsefInvoice> first,
            Specification<KsefInvoice> second) {
        return second == null ? first : first.and(second);
    }

    @Transactional(readOnly = true)
    public List<KsefSupplierAccessResponse> list(UUID storeId) {
        Set<UUID> granted = new HashSet<>(access.findActiveUserIds(storeId));
        return tasks.findAssignees(storeId).stream().map(user -> response(user, storeId, granted)).toList();
    }

    @Transactional
    public List<KsefSupplierAccessResponse> set(UUID storeId, UUID userId, boolean allSuppliers, UUID actorId) {
        AppUser user = tasks.findAssignees(storeId).stream().filter(candidate -> candidate.getId().equals(userId))
                .findFirst().orElseThrow(() -> ApiException.notFound("To konto nie ma dostępu do sklepu"));
        if (reason(user, storeId, false) != null) {
            throw ApiException.badRequest("To konto widzi wszystkich dostawców z urzędu");
        }
        AppUser actor = users.getReferenceById(actorId);
        var current = access.findActive(storeId, userId);
        if (allSuppliers && current.isEmpty()) {
            access.save(new KsefAllSuppliersAccess(stores.getReferenceById(storeId), user, actor));
        } else if (!allSuppliers) {
            current.ifPresent(entry -> entry.markRemoved(actor));
        }
        access.flush();
        return list(storeId);
    }

    private KsefSupplierAccessResponse response(AppUser user, UUID storeId, Set<UUID> granted) {
        String reason = reason(user, storeId, granted.contains(user.getId()));
        return new KsefSupplierAccessResponse(user.getId(), user.getDisplayName(), user.rankNameIn(storeId),
                reason == null ? "NONE" : reason, reason != null);
    }

    /** Powód widzenia wszystkich dostawców, {@code null} = tylko dostawcy z listy. */
    private static String reason(AppUser user, UUID storeId, boolean granted) {
        if (user.isOwner()) {
            return "OWNER";
        }
        StoreRole rank = user.rankIn(storeId);
        if (rank != null && rank.isAdmin()) {
            return "ADERON";
        }
        if (rank != null && ACCOUNTING_RANK.equals(rank.getKey())) {
            return "ACCOUNTING";
        }
        return granted ? "GRANTED" : null;
    }
}
