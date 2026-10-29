package com.intermanage.backend.document.repository;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.DeliveryReportClosingDelivery;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentGratis;
import com.intermanage.backend.document.entity.DocumentKsefInvoice;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentManualInvoice;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.ksef.entity.KsefInvoice;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Wyszukiwanie i sortowanie listy dokumentów jednego typu po stronie bazy (dokumentów są tysiące, przeglądarka
 * dostaje je stronami). Zapytanie dzielimy na słowa; każde musi wystąpić jako fragment numeru, dostawcy, jego kodu
 * albo numeru dokumentu dostawcy - bez względu na wielkość liter i polskie znaki ("slezak" znajdzie "ŚLĘZAK").
 */
public final class DocumentSearch {

    /** Kolumny listy, po których można sortować: nazwa z API -> pole encji. */
    private static final Map<String, String> SORTABLE = Map.of(
            "number", "number",
            "date", "documentDate",
            "supplier", "supplierName",
            "supplierDocument", "supplierDocumentNumber",
            "lines", "lineCount",
            "purchase", "purchaseValue",
            "sale", "saleValueGross");

    private static final String POLISH = "ąćęłńóśźż";
    private static final String PLAIN = "acelnoszz";

    private DocumentSearch() {
    }

    /** Słowa zapytania: małe litery bez znaków diakrytycznych i bez znaków specjalnych LIKE. */
    public static List<String> words(String query) {
        if (query == null) {
            return List.of();
        }
        String plain = Normalizer.normalize(query.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return Arrays.stream(plain.replace("%", "").replace("_", "").trim().split("\\s+"))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /**
     * Sortowanie po kolumnie z listy; nieznana kolumna = po numerze. Puste wartości zawsze na końcu, remis po id.
     * Numer jest tekstem, więc najpierw porównujemy jego długość: inaczej "9999" byłby większy niż "10000".
     */
    public static Sort sort(String column, String direction) {
        String field = SORTABLE.getOrDefault(column, "number");
        Sort.Direction sortDirection = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort.Order order = new Sort.Order(sortDirection, field).nullsLast();
        Sort byNumber = Sort.by(new Sort.Order(sortDirection, "numberLength"), new Sort.Order(sortDirection, "number"));
        return switch (field) {
            case "number" -> byNumber.and(Sort.by(Sort.Order.asc("id")));
            // Dokumenty z jednego dnia po numerze w tym samym kierunku (najnowszy dzień: najnowszy bon na górze).
            case "documentDate" -> Sort.by(order).and(byNumber).and(Sort.by(Sort.Order.asc("id")));
            default -> Sort.by(order, Sort.Order.asc("id"));
        };
    }

    public static Specification<Document> inStoreMatching(UUID storeId, DocumentType type, List<String> words) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("store").get("id"), storeId));
            all.add(cb.equal(root.get("type"), type));
            for (String word : words) {
                String contains = "%" + word + "%";
                all.add(cb.or(
                        cb.like(plain(cb, root.get("number")), contains),
                        cb.like(plain(cb, root.get("supplierName")), contains),
                        cb.like(plain(cb, root.get("supplierCode")), contains),
                        cb.like(plain(cb, root.get("supplierDocumentNumber")), contains)));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /** Filtr „ma / nie ma” (np. faktury, korekt) z parametru API: {@code with}, {@code without}, inaczej wszystkie. */
    public enum Presence {
        ALL, WITH, WITHOUT;

        public static Presence of(String value) {
            return "with".equalsIgnoreCase(value) ? WITH : "without".equalsIgnoreCase(value) ? WITHOUT : ALL;
        }
    }

    /**
     * Dokumenty z linią, w której każde słowo jest fragmentem nazwy, marki, EAN-u albo ITM8 artykułu (wszystkie słowa
     * w tej samej linii, bez względu na wielkość liter i polskie znaki).
     */
    public static Specification<Document> withProduct(List<String> words) {
        return (root, query, cb) -> {
            if (words.isEmpty()) {
                return cb.conjunction();
            }
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<DocumentLine> line = subquery.from(DocumentLine.class);
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(line.get("document"), root));
            for (String word : words) {
                String contains = "%" + word + "%";
                all.add(cb.or(
                        cb.like(plain(cb, line.get("name")), contains),
                        cb.like(plain(cb, line.get("brand")), contains),
                        cb.like(line.get("ean"), contains),
                        cb.like(line.get("itm8"), contains)));
            }
            subquery.select(cb.literal(1)).where(all.toArray(Predicate[]::new));
            return cb.exists(subquery);
        };
    }

    /**
     * Przyjęcia i korekty od dostawcy z fakturą KSeF albo bez: przypiętą (nieodpiętą) albo, gdy sklep jest połączony
     * z KSeF ({@code nip}), z faktury firmy o numerze równym numerowi dokumentu dostawcy. Faktura spoza KSeF wpisana
     * ręcznie też się liczy.
     */
    public static Specification<Document> withInvoice(Presence presence, String nip) {
        return (root, query, cb) -> {
            if (presence == Presence.ALL) {
                return cb.conjunction();
            }
            Subquery<Integer> linked = query.subquery(Integer.class);
            Root<DocumentKsefInvoice> link = linked.from(DocumentKsefInvoice.class);
            linked.select(cb.literal(1)).where(cb.equal(link.get("document"), root), cb.isNull(link.get("unlinkedAt")));
            Subquery<Integer> manual = query.subquery(Integer.class);
            Root<DocumentManualInvoice> manualInvoice = manual.from(DocumentManualInvoice.class);
            manual.select(cb.literal(1)).where(cb.equal(manualInvoice.get("document"), root),
                    cb.isNull(manualInvoice.get("removedAt")));
            Predicate has = cb.or(cb.exists(linked), cb.exists(manual));
            if (nip != null) {
                Subquery<Integer> byNumber = query.subquery(Integer.class);
                Root<KsefInvoice> invoice = byNumber.from(KsefInvoice.class);
                byNumber.select(cb.literal(1)).where(cb.equal(invoice.get("nip"), nip),
                        cb.equal(cb.upper(invoice.get("invoiceNumber")), cb.upper(root.get("supplierDocumentNumber"))));
                has = cb.or(has, cb.and(cb.equal(root.get("type"), DocumentType.PZ), cb.exists(byNumber)));
            }
            return presence == Presence.WITH ? has : cb.not(has);
        };
    }

    /** Przyjęcia z przypiętymi (nieodpiętymi) korektami albo bez. */
    public static Specification<Document> withCorrections(Presence presence) {
        return (root, query, cb) -> {
            if (presence == Presence.ALL) {
                return cb.conjunction();
            }
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<DeliveryCorrection> link = subquery.from(DeliveryCorrection.class);
            subquery.select(cb.literal(1)).where(cb.equal(link.get("delivery"), root), cb.isNull(link.get("unlinkedAt")));
            return presence == Presence.WITH ? cb.exists(subquery) : cb.not(cb.exists(subquery));
        };
    }

    /**
     * Przyjęcia, które poszły w aktywnym zamkniętym raporcie okresowym razem ze swoim okresem ({@code WITH}), albo
     * jeszcze w żadnym ({@code WITHOUT}). Zmiana przeniesiona do raportu kolejnego okresu nie zamyka przyjęcia.
     */
    public static Specification<Document> inReportClosing(Presence presence) {
        return (root, query, cb) -> {
            if (presence == Presence.ALL) {
                return cb.conjunction();
            }
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<DeliveryReportClosingDelivery> entry = subquery.from(DeliveryReportClosingDelivery.class);
            subquery.select(cb.literal(1)).where(cb.equal(entry.get("delivery"), root),
                    cb.isNull(entry.get("originClosing")), cb.isNull(entry.get("closing").get("cancelledAt")));
            return presence == Presence.WITH ? cb.exists(subquery) : cb.not(cb.exists(subquery));
        };
    }

    /**
     * Bez przyjęć za 0 zł: system sklepu zostawia przyjęcie, na które nic nie przyszło (wszystkie linie z ilością 0),
     * a takie tylko zaśmiecają listę. Inne typy zostają, bo tam 0 zł bywa prawdziwe (np. gratisy).
     */
    public static Specification<Document> withoutZeroDeliveries() {
        return (root, query, cb) -> cb.or(cb.notEqual(root.get("type"), DocumentType.PZ),
                cb.notEqual(root.get("purchaseValue"), BigDecimal.ZERO));
    }

    /** Kwota dokumentu z raportu, po której działa filtr zakresu: wartość zakupu albo sprzedaży brutto. */
    public enum ValueField {
        PURCHASE("purchaseValue"), SALE("saleValueGross");

        private final String attribute;

        ValueField(String attribute) {
            this.attribute = attribute;
        }

        /** {@code sale} = sprzedaż brutto, inaczej zakup. */
        public static ValueField of(String value) {
            return "sale".equalsIgnoreCase(value) ? SALE : PURCHASE;
        }
    }

    /**
     * Bez dokumentów, których kwota (z raportu, ze znakiem) leży w przedziale [from, to]; brak granicy = przedział
     * otwarty z tej strony, odwrócone granice są zamieniane. Bez obu granic niczego nie ukrywa.
     */
    public static Specification<Document> outsideValueRange(ValueField field, BigDecimal from, BigDecimal to) {
        return (root, query, cb) -> {
            if (from == null && to == null) {
                return cb.conjunction();
            }
            BigDecimal low = from;
            BigDecimal high = to;
            if (low != null && high != null && low.compareTo(high) > 0) {
                low = to;
                high = from;
            }
            Expression<BigDecimal> value = root.get(field.attribute);
            List<Predicate> inRange = new ArrayList<>();
            if (low != null) {
                inRange.add(cb.greaterThanOrEqualTo(value, low));
            }
            if (high != null) {
                inRange.add(cb.lessThanOrEqualTo(value, high));
            }
            return cb.not(cb.and(inRange.toArray(Predicate[]::new)));
        };
    }

    /**
     * Filtr gratisów z parametru API: {@code with} zarejestrowane jako gratis, {@code without} bez gratisu,
     * {@code candidate} jeszcze nieoznaczone z pozycją GRATIS (mogą być gratisem), inaczej wszystkie.
     */
    public enum GratisFilter {
        ALL, WITH, WITHOUT, CANDIDATE;

        public static GratisFilter of(String value) {
            return "candidate".equalsIgnoreCase(value) ? CANDIDATE
                    : switch (Presence.of(value)) {
                        case WITH -> WITH;
                        case WITHOUT -> WITHOUT;
                        case ALL -> ALL;
                    };
        }
    }

    /** Dokumenty zarejestrowane jako gratis od dostawcy (aktywny, niecofnięty wpis), bez gratisu albo kandydaci. */
    public static Specification<Document> withGratis(GratisFilter filter) {
        return (root, query, cb) -> {
            if (filter == GratisFilter.ALL) {
                return cb.conjunction();
            }
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<DocumentGratis> gratis = subquery.from(DocumentGratis.class);
            subquery.select(cb.literal(1)).where(cb.equal(gratis.get("document"), root), cb.isNull(gratis.get("deletedAt")));
            return switch (filter) {
                case WITH -> cb.exists(subquery);
                case WITHOUT -> cb.not(cb.exists(subquery));
                case CANDIDATE -> cb.and(cb.isTrue(root.get("gratisCandidate")), cb.not(cb.exists(subquery)));
                case ALL -> cb.conjunction();
            };
        };
    }

    /** Bez dokumentów dostawców o tych kodach; dokumenty bez wspólnego dostawcy zostają. */
    public static Specification<Document> withoutSuppliers(Collection<String> supplierCodes) {
        return (root, query, cb) -> supplierCodes.isEmpty() ? cb.conjunction()
                : cb.or(cb.isNull(root.get("supplierCode")), cb.not(root.get("supplierCode").in(supplierCodes)));
    }

    /** Dokumenty z datą w przedziale [from, to) - podpowiedzi przy przypinaniu do zamówienia garmażu. */
    public static Specification<Document> dated(LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("documentDate"), from),
                cb.lessThan(root.get("documentDate"), to));
    }

    /** lower() i polskie litery zamienione na łacińskie (translate w PostgreSQL). */
    private static Expression<String> plain(CriteriaBuilder cb, Expression<String> value) {
        return cb.function("translate", String.class, cb.lower(value), cb.literal(POLISH), cb.literal(PLAIN));
    }
}
