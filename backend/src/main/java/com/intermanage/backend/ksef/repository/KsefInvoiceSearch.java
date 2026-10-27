package com.intermanage.backend.ksef.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentKsefInvoice;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentSearch;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.entity.KsefInvoiceEntry;
import com.intermanage.backend.ksef.entity.KsefSkippedSupplier;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filtry listy faktur KSeF po stronie bazy. Faktury są wspólne dla firmy (NIP połączenia sklepu). Sklep bez IDWew
 * widzi wszystkie faktury firmy, sklep z IDWew - te ze swoimi IDWew i wszystkie bez IDWew (albo, na żądanie, tylko
 * ze swoimi). Faktury pominiętych dostawców tylko na żądanie. "Wprowadzona" = w którymkolwiek sklepie firmy (sklepy
 * z połączeniem KSeF z tym samym NIP-em) numer faktury jest numerem dokumentu dostawcy w przyjęciu PZ (bez względu na
 * wielkość liter), sklep przypiął do niej dokument albo oznaczył ją ręcznie - faktura wprowadzona w jednym sklepie
 * jest wprowadzona we wszystkich sklepach firmy.
 */
public final class KsefInvoiceSearch {

    /**
     * {@code internalId}: konkretny IDWew sklepu, {@link #OWN_INTERNAL_IDS} (tylko z IDWew sklepu),
     * {@link #WITHOUT_INTERNAL_ID} (tylko bez IDWew) albo {@code null} (wszystkie widoczne w sklepie).
     */
    public record Filter(String query, LocalDate from, LocalDate to, Entered entered, Kind kind, String internalId,
            boolean showSkipped) {
    }

    public enum Entered {
        ALL, ENTERED, NOT_ENTERED
    }

    public enum Kind {
        ALL, INVOICES, CORRECTIONS
    }

    public static final String WITHOUT_INTERNAL_ID = "NONE";
    public static final String OWN_INTERNAL_IDS = "OWN";

    private static final Map<String, String> SORTABLE = Map.of(
            "issueDate", "issueDate",
            "number", "invoiceNumber",
            "seller", "sellerName",
            "net", "netAmount",
            "gross", "grossAmount");

    private static final String POLISH = "ąćęłńóśźż";
    private static final String PLAIN = "acelnoszz";

    private KsefInvoiceSearch() {
    }

    /** Domyślnie najnowsze na górze; remis po numerze KSeF. */
    public static Sort sort(String column, String direction) {
        String field = SORTABLE.getOrDefault(column, "issueDate");
        Sort.Order order = "asc".equalsIgnoreCase(direction) ? Sort.Order.asc(field) : Sort.Order.desc(field);
        return Sort.by(order.nullsLast(), Sort.Order.desc("ksefNumber"));
    }

    public static Specification<KsefInvoice> matching(UUID storeId, String nip, Collection<String> ownInternalIds,
            Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("nip"), nip));
            all.add(visibleInStore(root, query, cb, ownInternalIds, filter.internalId()));
            if (!filter.showSkipped()) {
                all.add(cb.not(cb.exists(skippedSupplier(root, query, cb, storeId))));
            }
            if (filter.from() != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("issueDate"), filter.from()));
            }
            if (filter.to() != null) {
                all.add(cb.lessThanOrEqualTo(root.get("issueDate"), filter.to()));
            }
            Predicate correction = cb.like(root.get("invoiceType"), "Kor%");
            if (filter.kind() == Kind.INVOICES) {
                all.add(cb.not(correction));
            } else if (filter.kind() == Kind.CORRECTIONS) {
                all.add(correction);
            }
            if (filter.entered() != Entered.ALL) {
                Predicate entered = cb.or(cb.exists(manualEntry(root, query, cb, nip)),
                        cb.exists(delivery(root, query, cb, nip)),
                        cb.exists(linkedDocument(root, query, cb, nip)));
                all.add(filter.entered() == Entered.ENTERED ? entered : cb.not(entered));
            }
            for (String word : DocumentSearch.words(filter.query())) {
                String contains = "%" + word + "%";
                all.add(cb.or(
                        cb.like(plain(cb, root.get("invoiceNumber")), contains),
                        cb.like(plain(cb, root.get("sellerName")), contains),
                        cb.like(root.get("sellerNip"), contains),
                        cb.like(plain(cb, root.get("ksefNumber")), contains)));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /**
     * Sklep bez IDWew: wszystkie faktury firmy. Sklep z IDWew: bez IDWew albo z jego IDWew; zawężenie do jego IDWew
     * (wszystkich albo jednego) albo do faktur bez IDWew.
     */
    private static Predicate visibleInStore(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            Collection<String> own, String selected) {
        Predicate without = cb.not(cb.exists(internalIds(root, query, cb, null)));
        if (WITHOUT_INTERNAL_ID.equals(selected)) {
            return without;
        }
        if (own.isEmpty()) {
            return selected == null ? cb.conjunction() : cb.disjunction();
        }
        Predicate withOwn = cb.exists(internalIds(root, query, cb, own));
        if (OWN_INTERNAL_IDS.equals(selected)) {
            return withOwn;
        }
        if (selected != null) {
            return own.contains(selected) ? cb.exists(internalIds(root, query, cb, List.of(selected))) : cb.disjunction();
        }
        return cb.or(without, withOwn);
    }

    /** To samo co {@link #visibleInStore} bez zawężeń, dla pojedynczej faktury. */
    public static boolean visibleInStore(KsefInvoice invoice, Collection<String> own) {
        return own.isEmpty() || invoice.getInternalIds().isEmpty()
                || invoice.getInternalIds().stream().anyMatch(ref -> own.contains(ref.internalId()));
    }

    /** IDWew faktury - wszystkie ({@code among} = null) albo spośród podanych. */
    private static Subquery<Integer> internalIds(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            Collection<String> among) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefInvoice> invoice = subquery.from(KsefInvoice.class);
        Join<KsefInvoice, KsefInvoice.InternalIdRef> ids = invoice.join("internalIds");
        Predicate same = cb.equal(invoice, root);
        subquery.select(cb.literal(1))
                .where(among == null ? same : cb.and(same, ids.get("internalId").in(among)));
        return subquery;
    }

    private static Subquery<Integer> skippedSupplier(Root<KsefInvoice> root, CriteriaQuery<?> query,
            CriteriaBuilder cb, UUID storeId) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefSkippedSupplier> skipped = subquery.from(KsefSkippedSupplier.class);
        subquery.select(cb.literal(1)).where(
                cb.equal(skipped.get("store").get("id"), storeId),
                cb.equal(skipped.get("nip"), root.get("sellerNip")),
                cb.isNull(skipped.get("removedAt")));
        return subquery;
    }

    /** Sklepy firmy: z połączeniem KSeF z tym NIP-em. */
    private static Subquery<UUID> companyStores(CriteriaQuery<?> query, CriteriaBuilder cb, String nip) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<KsefConnection> connection = subquery.from(KsefConnection.class);
        subquery.select(connection.get("storeId")).where(cb.equal(connection.get("nip"), nip));
        return subquery;
    }

    private static Subquery<Integer> manualEntry(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            String nip) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<KsefInvoiceEntry> entry = subquery.from(KsefInvoiceEntry.class);
        subquery.select(cb.literal(1)).where(
                entry.get("id").get("storeId").in(companyStores(query, cb, nip)),
                cb.equal(entry.get("id").get("invoiceId"), root.get("id")));
        return subquery;
    }

    private static Subquery<Integer> delivery(Root<KsefInvoice> root, CriteriaQuery<?> query, CriteriaBuilder cb,
            String nip) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<Document> document = subquery.from(Document.class);
        subquery.select(cb.literal(1)).where(
                document.get("store").get("id").in(companyStores(query, cb, nip)),
                cb.equal(document.get("type"), DocumentType.PZ),
                cb.equal(cb.upper(document.get("supplierDocumentNumber")), cb.upper(root.get("invoiceNumber"))));
        return subquery;
    }

    /** Dokument sklepu firmy (przyjęcie, korekta od dostawcy) przypięty do faktury ręcznie i nieodpięty. */
    private static Subquery<Integer> linkedDocument(Root<KsefInvoice> root, CriteriaQuery<?> query,
            CriteriaBuilder cb, String nip) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<DocumentKsefInvoice> link = subquery.from(DocumentKsefInvoice.class);
        subquery.select(cb.literal(1)).where(
                cb.equal(link.get("invoice"), root),
                link.get("document").get("store").get("id").in(companyStores(query, cb, nip)),
                cb.isNull(link.get("unlinkedAt")));
        return subquery;
    }

    private static Expression<String> plain(CriteriaBuilder cb, Expression<String> value) {
        return cb.function("translate", String.class, cb.lower(value), cb.literal(POLISH), cb.literal(PLAIN));
    }
}
