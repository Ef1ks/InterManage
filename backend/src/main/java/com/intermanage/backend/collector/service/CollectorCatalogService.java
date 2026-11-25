package com.intermanage.backend.collector.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.collector.component.CollectorCatalogFile;
import com.intermanage.backend.collector.repository.CollectorCatalogRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.product.entity.ProductImport;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Plik kartoteki sklepu dla kolektorów ({@link CollectorCatalogFile}, spakowany zlib). Powstaje raz na wersję danych
 * i czeka w pamięci: kolektory sklepu zwykle stają na podstawce o tej samej porze i pobierają to samo. Bez cen zakupu
 * i marż, bo kolektor nie ma logowania osoby.
 */
@Service
public class CollectorCatalogService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** Ile dalszych EAN-ów artykułu pokazuje karta na kolektorze (wszystkie są w indeksie do skanowania). */
    private static final int SHOWN_EANS = 20;

    /** Gotowy plik: treść spakowana, długość po rozpakowaniu i SHA-256 rozpakowanej treści. */
    public record CatalogFile(String version, byte[] content, int plainSize, String sha256) {
    }

    private final CollectorCatalogRepository catalog;
    private final ProductImportRepository imports;
    private final NomenclatureRepository nomenclatures;
    private final DocumentLineRepository lines;
    private final StoreRepository stores;
    private final TransactionTemplate readOnly;
    private final Map<UUID, CatalogFile> files = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public CollectorCatalogService(CollectorCatalogRepository catalog, ProductImportRepository imports,
            NomenclatureRepository nomenclatures, DocumentLineRepository lines, StoreRepository stores,
            PlatformTransactionManager transactions) {
        this.catalog = catalog;
        this.imports = imports;
        this.nomenclatures = nomenclatures;
        this.lines = lines;
        this.stores = stores;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Wersja danych sklepu; liczona z ostatnich importów i dnia (promocje kończą się z dniem), bez budowania pliku. */
    public String version(UUID storeId) {
        String source = CollectorCatalogFile.MAGIC + "|" + LocalDate.now(StoreTime.ZONE) + "|"
                + readOnly.execute(status -> catalog.fingerprint(storeId));
        return sha256(source.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public CatalogFile file(UUID storeId) {
        synchronized (locks.computeIfAbsent(storeId, ignored -> new Object())) {
            String version = version(storeId);
            CatalogFile ready = files.get(storeId);
            if (ready == null || !ready.version().equals(version)) {
                // Wersja sprzed budowania: import w trakcie da kolejnemu pytaniu nową wersję i plik powstanie jeszcze raz.
                byte[] plain = readOnly.execute(status -> build(storeId, version));
                ready = new CatalogFile(version, deflate(plain), plain.length, sha256(plain));
                files.put(storeId, ready);
            }
            return ready;
        }
    }

    private byte[] build(UUID storeId, String version) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        UUID importId = imports.findNewestReportIds(storeId, Limit.of(1)).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("Sklep nie ma jeszcze wgranej kartoteki"));
        LocalDateTime reportAt = imports.findById(importId).map(ProductImport::getReportGeneratedAt).orElse(null);
        LocalDate today = LocalDate.now(StoreTime.ZONE);

        Map<String, Set<String>> codes = grouped(catalog.findCodes(storeId, importId));
        Map<String, Set<String>> formerCodes = grouped(catalog.findFormerCodes(storeId, importId));
        Map<String, CollectorCatalogRepository.Promo> promos = promotions(storeId, today);
        Map<String, DocumentLineRepository.LastDelivery> deliveries = lines
                .findLastDeliveries(storeId, LocalDateTime.now(StoreTime.ZONE).minusYears(1)).stream()
                .collect(Collectors.toMap(DocumentLineRepository.LastDelivery::getItm8, row -> row, (a, b) -> a));

        List<CollectorCatalogFile.Article> articles = new ArrayList<>();
        for (CollectorCatalogRepository.Article row : catalog.findArticles(storeId, importId)) {
            Collection<String> current = codes.getOrDefault(row.getItm8(), Set.of());
            Collection<String> former = formerCodes.getOrDefault(row.getItm8(), Set.of());
            articles.add(new CollectorCatalogFile.Article(
                    fields(row, promos.get(row.getItm8()), deliveries.get(row.getItm8()), current, today),
                    row.getItm8(), row.getName(), brand(row), row.getEan(), current, former));
        }

        Map<String, String> header = new LinkedHashMap<>();
        header.put("version", version);
        header.put("store", store.getNumber());
        header.put("storeName", store.getName());
        header.put("generated", MINUTE.format(LocalDateTime.now(StoreTime.ZONE)));
        header.put("report", reportAt == null ? "" : MINUTE.format(reportAt));
        header.put("count", String.valueOf(articles.size()));
        Map<String, String> names = new LinkedHashMap<>();
        for (Nomenclature item : nomenclatures.findAllByStore(storeId)) {
            names.put(item.getCode(), item.getName());
        }
        return CollectorCatalogFile.write(header, articles, names);
    }

    private static List<String> fields(CollectorCatalogRepository.Article row, CollectorCatalogRepository.Promo listed,
            DocumentLineRepository.LastDelivery delivery, Collection<String> codes, LocalDate today) {
        // Promocja z kasy jest nowsza niż lista promocji; bez niej trwająca albo najbliższa z listy.
        boolean till = row.getPromoPrice() != null && row.getPromoTo() != null && !row.getPromoTo().isBefore(today);
        BigDecimal promoPrice = till ? row.getPromoPrice() : listed == null ? null : listed.getPromoPrice();
        LocalDate promoFrom = till ? row.getPromoFrom() : listed == null ? null : listed.getStartDate();
        LocalDate promoTo = till ? row.getPromoTo() : listed == null ? null : listed.getEndDate();
        BigDecimal promoRegular = till ? row.getSalePrice() : listed == null ? null : listed.getRegularPrice();
        String supplier = delivery == null ? "" : Stream.of(delivery.getSupplierCode(), delivery.getSupplierName())
                .filter(Objects::nonNull).collect(Collectors.joining(" "));
        String eans = codes.stream().filter(code -> CollectorCatalogFile.number(code) != CollectorCatalogFile.number(row.getEan()))
                .limit(SHOWN_EANS).collect(Collectors.joining(" "));
        return List.of(text(row.getItm8()), text(row.getEan()), text(row.getName()), brand(row),
                text(row.getSubfamilyCode()), number(row.getSalePrice()), number(promoPrice), day(promoFrom),
                day(promoTo), number(promoRegular), number(row.getStock()), number(row.getRotation()),
                number(row.getCaseSize()), text(row.getUnit()), number(row.getNetContent()),
                number(row.getCadencierPrice()), Boolean.FALSE.equals(row.getActive()) ? "0" : "1",
                Boolean.TRUE.equals(row.getCadencier()) ? "1" : "0", supplier,
                delivery == null ? "" : day(delivery.getDocumentDate().toLocalDate()), eans);
    }

    /** Promocja z listy: trwająca dziś, a bez niej najbliższa przyszła. */
    private Map<String, CollectorCatalogRepository.Promo> promotions(UUID storeId, LocalDate today) {
        Map<String, CollectorCatalogRepository.Promo> result = new HashMap<>();
        for (CollectorCatalogRepository.Promo promo : catalog.findPromotions(storeId, today)) {
            result.putIfAbsent(promo.getItm8(), promo);
        }
        return result;
    }

    private static Map<String, Set<String>> grouped(List<CollectorCatalogRepository.Code> codes) {
        Map<String, Set<String>> result = new HashMap<>();
        for (CollectorCatalogRepository.Code code : codes) {
            if (code.getEan() != null) {
                result.computeIfAbsent(code.getItm8(), ignored -> new LinkedHashSet<>()).add(code.getEan());
            }
        }
        return result;
    }

    /** Marka; kartoteka ma przy części artykułów „0” zamiast pustej. */
    private static String brand(CollectorCatalogRepository.Article row) {
        return row.getBrand() == null || row.getBrand().strip().equals("0") ? "" : row.getBrand();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String day(LocalDate value) {
        return value == null ? "" : DAY.format(value);
    }

    private static byte[] deflate(byte[] plain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length / 4);
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out, new Deflater(Deflater.BEST_COMPRESSION))) {
            zip.write(plain);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
