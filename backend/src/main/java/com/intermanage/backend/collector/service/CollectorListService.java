package com.intermanage.backend.collector.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.collector.component.CollectorCatalogFile;
import com.intermanage.backend.collector.component.CollectorListPdf;
import com.intermanage.backend.collector.dto.CollectorListDetails;
import com.intermanage.backend.collector.dto.CollectorListResponse;
import com.intermanage.backend.collector.entity.CollectorList;
import com.intermanage.backend.collector.entity.CollectorListLine;
import com.intermanage.backend.collector.entity.CollectorListOutput;
import com.intermanage.backend.collector.entity.CollectorListProtocol;
import com.intermanage.backend.collector.entity.StoreCollector;
import com.intermanage.backend.collector.mapper.CollectorListMapper;
import com.intermanage.backend.collector.repository.CollectorListLineRepository;
import com.intermanage.backend.collector.repository.CollectorListProtocolRepository;
import com.intermanage.backend.collector.repository.CollectorListRepository;
import com.intermanage.backend.collector.repository.StoreCollectorRepository;
import com.intermanage.backend.collector.service.StoreCollectorService.CollectorContext;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.component.Margin;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.ProtocolRequest;
import com.intermanage.backend.dataentry.service.DataEntryProtocolService;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.entity.ProtocolKind;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Listy zeskanowane na kolektorach: przyjęcie listy z podstawki, podgląd z cenami z kartoteki, protokół (PROTO1-PROTO6
 * tym samym plikiem co z wprowadzania danych), Excel i wydruk. Kilka list można złożyć w jeden protokół.
 */
@Service
public class CollectorListService {

    static final int LIST_LIMIT = 100;
    private static final int MAX_LINES = 5000;
    private static final int FIELDS = 8;
    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999");

    private final CollectorListRepository lists;
    private final CollectorListLineRepository lines;
    private final CollectorListProtocolRepository protocols;
    private final StoreCollectorRepository collectors;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final DataEntryProtocolService protocolFiles;
    private final CollectorListMapper mapper;
    private final CollectorListPdf pdf;
    private final XlsxWriter xlsx;
    private final LiveEvents live;

    public CollectorListService(CollectorListRepository lists, CollectorListLineRepository lines,
            CollectorListProtocolRepository protocols, StoreCollectorRepository collectors, ProductRepository products,
            StoreRepository stores, AppUserRepository users, DataEntryProtocolService protocolFiles,
            CollectorListMapper mapper, CollectorListPdf pdf, XlsxWriter xlsx, LiveEvents live) {
        this.lists = lists;
        this.lines = lines;
        this.protocols = protocols;
        this.collectors = collectors;
        this.products = products;
        this.stores = stores;
        this.users = users;
        this.protocolFiles = protocolFiles;
        this.mapper = mapper;
        this.pdf = pdf;
        this.xlsx = xlsx;
        this.live = live;
    }

    /**
     * Lista z kolektora: wiersz na linię, pola oddzielone tabulatorem (ITM8, zeskanowany kod, główny EAN, nazwa, marka,
     * wpisana ilość, ilość w jednostce głównego kodu, numer). Ta sama lista drugi raz (urwane potwierdzenie) jest pomijana.
     */
    @Transactional
    public void receive(CollectorContext context, UUID id, String started, String text) {
        CollectorList existing = lists.findById(id).orElse(null);
        if (existing != null) {
            if (!existing.getCollector().getId().equals(context.collectorId())) {
                throw ApiException.conflict("Ta lista jest już z innego kolektora");
            }
            return;
        }
        List<CollectorListLine> parsed = parse(id, text);
        StoreCollector collector = collectors.findById(context.collectorId())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kolektora"));
        lists.saveAndFlush(new CollectorList(id, collector, started(started), parsed.size()));
        lines.saveAll(parsed);
        live.publish(context.storeId(), "collector-lists", Map.of("action", "received", "id", id.toString(),
                "collector", collector.getName(), "lines", parsed.size()));
    }

    @Transactional(readOnly = true)
    public List<CollectorListResponse> list(UUID storeId) {
        return responses(lists.findLatest(storeId, Limit.of(LIST_LIMIT)));
    }

    @Transactional(readOnly = true)
    public long countWaiting(UUID storeId) {
        return lists.countWaiting(storeId);
    }

    @Transactional(readOnly = true)
    public CollectorListDetails details(UUID storeId, Collection<UUID> ids) {
        List<CollectorList> found = find(storeId, ids);
        Map<String, List<CollectorListLine>> byArticle = lines.findByLists(found.stream().map(CollectorList::getId).toList())
                .stream().collect(Collectors.groupingBy(CollectorListLine::getItm8, LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, Product> catalog = products.findByStoreIdAndItm8In(storeId, byArticle.keySet()).stream()
                .collect(Collectors.toMap(Product::getItm8, Function.identity()));
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        List<CollectorListDetails.Row> rows = new ArrayList<>();
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal purchase = BigDecimal.ZERO;
        BigDecimal sale = BigDecimal.ZERO;
        for (List<CollectorListLine> scanned : byArticle.values()) {
            CollectorListDetails.Row row = row(scanned, catalog.get(scanned.getFirst().getItm8()), today);
            rows.add(row);
            quantity = quantity.add(row.quantity());
            if (row.purchaseValue() != null) {
                purchase = purchase.add(row.purchaseValue());
            }
            if (row.saleValue() != null) {
                sale = sale.add(row.saleValue());
            }
        }
        return new CollectorListDetails(responses(found), rows, quantity, purchase.setScale(2, RoundingMode.HALF_UP), sale);
    }

    /** Plik protokołu; zapisanie go na dysku potwierdza {@link #markSaved}. */
    @Transactional(readOnly = true)
    public DataEntryProtocolService.ProtocolFile protocol(UUID storeId, ProtocolKind kind, Collection<UUID> ids,
            String username) {
        List<ProtocolRequest.Line> articles = details(storeId, ids).rows().stream()
                .filter(row -> row.quantity().signum() != 0)
                .map(row -> new ProtocolRequest.Line(row.ean(), row.quantity()))
                .toList();
        return protocolFiles.generate(storeId, kind, new ProtocolRequest(articles), username);
    }

    @Transactional
    public List<CollectorListResponse> markSaved(UUID storeId, CollectorListOutput kind, Collection<UUID> ids,
            String fileName,
            UUID actorId) {
        if (fileName == null || fileName.isBlank()) {
            throw ApiException.badRequest("Brak nazwy pliku protokołu");
        }
        for (CollectorList list : find(storeId, ids)) {
            protocols.save(new CollectorListProtocol(list.getId(), kind, fileName, users.getReferenceById(actorId)));
        }
        protocols.flush();
        return list(storeId);
    }

    /** Cofnięcie oznaczenia (np. plik trafił w złe miejsce): lista znów czeka na protokół. */
    @Transactional
    public List<CollectorListResponse> undoProtocol(UUID storeId, UUID listId, UUID protocolId, UUID actorId) {
        find(storeId, List.of(listId));
        protocols.findById(protocolId)
                .filter(found -> found.getListId().equals(listId) && found.getUndoneAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono protokołu"))
                .undo(users.getReferenceById(actorId));
        protocols.flush();
        return list(storeId);
    }

    @Transactional
    public List<CollectorListResponse> remove(UUID storeId, UUID listId, UUID actorId) {
        find(storeId, List.of(listId)).getFirst().markDeleted(users.getReferenceById(actorId));
        lists.flush();
        return list(storeId);
    }

    @Transactional
    public List<CollectorListResponse> restore(UUID storeId, UUID listId) {
        lists.findById(listId)
                .filter(found -> found.getStoreId().equals(storeId) && found.getDeletedAt() != null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono listy"))
                .restore();
        lists.flush();
        return list(storeId);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(UUID storeId, Collection<UUID> ids, String printedBy) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        return pdf.pdf(store.getNumber(), details(storeId, ids), printedBy);
    }

    @Transactional(readOnly = true)
    public byte[] xlsx(UUID storeId, Collection<UUID> ids) {
        CollectorListDetails details = details(storeId, ids);
        List<XlsxWriter.Row> rows = new ArrayList<>();
        for (CollectorListDetails.Row row : details.rows()) {
            rows.add(XlsxWriter.Row.of(itm8(row.itm8()), row.ean(), row.name(), row.brand(), money(row.purchasePrice()),
                    money(row.purchaseValue()), money(row.salePrice()), money(row.saleValue()), row.marginPercent(), row.quantity()));
        }
        rows.add(XlsxWriter.Row.of(XlsxWriter.Cell.bold("Razem"), null, null, null, null,
                XlsxWriter.Cell.boldMoney(details.totalPurchaseValue()), null,
                XlsxWriter.Cell.boldMoney(details.totalSaleValue()), null, XlsxWriter.Cell.bold(details.totalQuantity())));
        return xlsx.writeRows("Kolektor", List.of(new XlsxWriter.Column("ITM8", 12), new XlsxWriter.Column("EAN", 16),
                new XlsxWriter.Column("Nazwa", 38), new XlsxWriter.Column("Marka", 18),
                new XlsxWriter.Column("Cena zakupu netto", 18),
                new XlsxWriter.Column("Wartość zakupu netto", 18), new XlsxWriter.Column("Cena sprzedaży szt.", 18),
                new XlsxWriter.Column("Wartość sprzedaży", 18), new XlsxWriter.Column("Marża %", 10),
                new XlsxWriter.Column("Ilość", 10)), rows);
    }

    private CollectorListDetails.Row row(List<CollectorListLine> scanned, Product product, LocalDate today) {
        CollectorListLine first = scanned.getFirst();
        BigDecimal quantity = scanned.stream().map(CollectorListLine::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add)
                .stripTrailingZeros();
        String ean = product != null && CollectorCatalogFile.number(product.getEan()) > 0 ? product.getEan() : first.getEan();
        BigDecimal purchase = product == null ? null : product.getPurchasePrice();
        BigDecimal sale = product == null ? null : salePrice(product, today);
        Integer vat = product == null ? null : vat(product.getVatCode());
        boolean plain = scanned.size() == 1
                && CollectorCatalogFile.number(first.getScannedCode()) == CollectorCatalogFile.number(ean)
                && first.getEnteredQuantity().compareTo(first.getQuantity()) == 0;
        List<CollectorListDetails.Scan> scans = plain ? List.of() : scanned.stream()
                .map(line -> new CollectorListDetails.Scan(line.getScannedCode(),
                        line.getEnteredQuantity().stripTrailingZeros(), line.getQuantity().stripTrailingZeros()))
                .toList();
        return new CollectorListDetails.Row(first.getItm8(), ean, product == null ? first.getName() : product.getName(),
                product == null ? first.getBrand() : product.getBrand(), purchase,
                purchase == null ? null : purchase.multiply(quantity).setScale(2, RoundingMode.HALF_UP), sale,
                sale == null ? null : sale.multiply(quantity).setScale(2, RoundingMode.HALF_UP),
                purchase == null || sale == null || sale.signum() <= 0 || vat == null ? null
                        : Margin.percent(sale, BigDecimal.valueOf(vat), purchase, 2),
                quantity, scans, product != null);
    }

    /** Cena na kasie: trwająca promocja z kasy, a bez niej cena z kartoteki. */
    private static BigDecimal salePrice(Product product, LocalDate today) {
        boolean promotion = product.getPromoPrice() != null && product.getPromoTo() != null
                && !product.getPromoTo().isBefore(today)
                && (product.getPromoFrom() == null || !product.getPromoFrom().isAfter(today));
        return promotion ? product.getPromoPrice() : product.getSalePrice();
    }

    /** Stawka VAT z kodu kartoteki (pozostałe kody, np. kaucje, bez stawki). */
    private static Integer vat(Short code) {
        return code == null ? null : switch (code) {
            case 1 -> 23;
            case 2 -> 8;
            case 6 -> 5;
            default -> null;
        };
    }

    private List<CollectorList> find(UUID storeId, Collection<UUID> ids) {
        List<CollectorList> found = lists.findActive(storeId, ids);
        if (ids.isEmpty() || found.size() != ids.stream().distinct().count()) {
            throw ApiException.notFound("Nie znaleziono listy z kolektora");
        }
        return found;
    }

    private List<CollectorListResponse> responses(List<CollectorList> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<CollectorListProtocol>> saved = protocols
                .findByLists(found.stream().map(CollectorList::getId).toList()).stream()
                .collect(Collectors.groupingBy(CollectorListProtocol::getListId));
        return found.stream().map(list -> mapper.toResponse(list, saved.getOrDefault(list.getId(), List.of()))).toList();
    }

    private static List<CollectorListLine> parse(UUID id, String text) {
        List<CollectorListLine> result = new ArrayList<>();
        for (String row : (text == null ? "" : text).split("\n")) {
            if (row.isBlank()) {
                continue;
            }
            String[] fields = row.replace("\r", "").split("\t", -1);
            if (fields.length < FIELDS || result.size() >= MAX_LINES) {
                throw ApiException.badRequest("Lista z kolektora jest uszkodzona");
            }
            if (!fields[0].matches("\\d{1,13}") || !fields[1].matches("\\d{1,20}") || !fields[2].matches("\\d{1,14}")) {
                throw ApiException.badRequest("Zły kod na liście z kolektora: " + cut(fields[1], 20));
            }
            result.add(new CollectorListLine(id, result.size() + 1, fields[0], fields[1], fields[2], cut(fields[3], 100),
                    cut(fields[4], 100), quantity(fields[5]), quantity(fields[6])));
        }
        if (result.isEmpty()) {
            throw ApiException.badRequest("Lista z kolektora jest pusta");
        }
        return result;
    }

    private static BigDecimal quantity(String value) {
        // Ujemna: kolektor dosyła zmiany listy wysłanej wcześniej, także zmniejszone ilości.
        if (!value.matches("-?\\d{1,9}(\\.\\d{1,3})?")) {
            throw ApiException.badRequest("Zła ilość na liście z kolektora: " + cut(value, 20));
        }
        BigDecimal quantity = new BigDecimal(value);
        if (quantity.abs().compareTo(MAX_QUANTITY) > 0) {
            throw ApiException.badRequest("Za duża ilość na liście z kolektora: " + value);
        }
        return quantity;
    }

    private static LocalDateTime started(String value) {
        try {
            return value == null ? null : LocalDateTime.parse(value, STARTED);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Object money(BigDecimal value) {
        return value == null ? null : XlsxWriter.Cell.money(value);
    }

    private static String itm8(String value) {
        return value.replaceFirst("^0+", "");
    }

    private static String cut(String value, int max) {
        String text = value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
