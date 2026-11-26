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

import com.intermanage.backend.cadtop.dto.CadPrices;
import com.intermanage.backend.cadtop.service.CadPriceService;
import com.intermanage.backend.collector.component.CollectorCatalogFile;
import com.intermanage.backend.collector.repository.CollectorCatalogRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.product.entity.DepositKind;
import com.intermanage.backend.product.entity.ProductImport;
import com.intermanage.backend.product.repository.DepositKindRepository;
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
    private final DepositKindRepository depositKinds;
    private final CadPriceService cadPrices;
    private final TransactionTemplate readOnly;
    private final Map<UUID, CatalogFile> files = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public CollectorCatalogService(CollectorCatalogRepository catalog, ProductImportRepository imports,
            NomenclatureRepository nomenclatures, DocumentLineRepository lines, StoreRepository stores,
            DepositKindRepository depositKinds, CadPriceService cadPrices, PlatformTransactionManager transactions) {
        this.depositKinds = depositKinds;
        this.cadPrices = cadPrices;
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

        List<CollectorCatalogRepository.Code> multiCodes = catalog.findCodes(storeId, importId);
        Map<String, Set<String>> codes = grouped(multiCodes);
        Map<String, List<CollectorCatalogRepository.Code>> codeRows = multiCodes.stream()
                .collect(Collectors.groupingBy(CollectorCatalogRepository.Code::getItm8));
        Map<Short, DepositKind> deposits = new HashMap<>();
        depositKinds.findAllByStore(storeId).forEach(kind -> deposits.put(kind.getCode(), kind));
        Map<String, Map<Long, BigDecimal>> packs = new HashMap<>();
        Map<String, Set<Long>> pieceCodes = new HashMap<>();
        for (CollectorCatalogRepository.Code code : multiCodes) {
            if (code.getPack() != null) {
                packs.computeIfAbsent(code.getItm8(), ignored -> new HashMap<>())
                        .put(CollectorCatalogFile.number(code.getEan()), code.getPack());
                // Kod na kg to etykieta wagi (0,1 = 100 g), a nie sztuka z wielopaku.
                if (!"kg".equalsIgnoreCase(code.getUnit())) {
                    pieceCodes.computeIfAbsent(code.getItm8(), ignored -> new LinkedHashSet<>())
                            .add(CollectorCatalogFile.number(code.getEan()));
                }
            }
        }
        Map<String, Set<String>> formerCodes = grouped(catalog.findFormerCodes(storeId, importId));
        Map<String, CollectorCatalogRepository.Promo> promos = promotions(storeId, today);
        Map<String, DocumentLineRepository.LastDelivery> deliveries = lines
                .findLastDeliveries(storeId, LocalDateTime.now(StoreTime.ZONE).minusYears(1)).stream()
                .collect(Collectors.toMap(DocumentLineRepository.LastDelivery::getItm8, row -> row, (a, b) -> a));

        List<CollectorCatalogRepository.Article> rows = catalog.findArticles(storeId, importId);
        // Cena zakupu z cadencier (dzisiejszy plik TOP) tylko artykułów aktywnych w cadencier: to cena centrali dla całej
        // sieci, a nie cena zakupu sklepu, więc kolektor bez logowania osoby może ją pokazać (okno ilości w protokole).
        Map<String, CadPrices.Price> cadencier = cadPrices.current(storeId, rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.getCadencier())).map(CollectorCatalogRepository.Article::getItm8)
                .toList()).byItm8();
        List<CollectorCatalogFile.Article> articles = new ArrayList<>();
        for (CollectorCatalogRepository.Article row : rows) {
            Collection<String> current = codes.getOrDefault(row.getItm8(), Set.of());
            Collection<String> former = formerCodes.getOrDefault(row.getItm8(), Set.of());
            List<String> fields = new ArrayList<>(fields(row, promos.get(row.getItm8()), deliveries.get(row.getItm8()),
                    current, today, multipack(row, packs.getOrDefault(row.getItm8(), Map.of()),
                            pieceCodes.getOrDefault(row.getItm8(), Set.of()))));
            fields.add(deposit(deposits, row.getDepositCode()));
            fields.add(labelCodes(row, codeRows.getOrDefault(row.getItm8(), List.of()), deposits));
            CadPrices.Price cad = cadencier.get(row.getItm8());
            fields.add(cad == null ? "" : number(cad.purchasePrice()));
            articles.add(new CollectorCatalogFile.Article(fields,
                    row.getItm8(), row.getName(), brand(row), row.getEan(), current, former,
                    packs.getOrDefault(row.getItm8(), Map.of())));
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
            DocumentLineRepository.LastDelivery delivery, Collection<String> codes, LocalDate today, int multipack) {
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
                delivery == null ? "" : day(delivery.getDocumentDate().toLocalDate()), eans,
                multipack > 1 ? String.valueOf(multipack) : "");
    }

    /**
     * Ile sztuk ma wielopak artykułu (0,25 → 4, 0,166 → 6): główny kod to wielopak, a inny kod jego sztuka. 0 = artykuł
     * bez takiego kodu. Przy kilku bierze najdrobniejszy.
     */
    private static int multipack(CollectorCatalogRepository.Article row, Map<Long, BigDecimal> packs,
            Set<Long> pieceCodes) {
        BigDecimal main = packs.get(CollectorCatalogFile.number(row.getEan()));
        int result = 0;
        for (Long code : pieceCodes) {
            CollectorCatalogFile.Ratio ratio = CollectorCatalogFile.Ratio.of(packs.get(code), main);
            if (ratio.isPiece()) {
                result = Math.max(result, ratio.denominator());
            }
        }
        return result;
    }

    /** Kaucja do cenówki: {@code wartość;nazwa} z listy opakowań zwrotnych; bez kodu, 0 zł albo nieznany kod pusta. */
    private static String deposit(Map<Short, DepositKind> deposits, Short code) {
        DepositKind kind = code == null ? null : deposits.get(code);
        if (kind == null || kind.getValue() == null || kind.getValue().signum() <= 0) {
            return "";
        }
        return number(kind.getValue()) + ";" + part(kind.getName());
    }

    /**
     * Inne kody artykułu z multikodów do cenówki: nazwa kodu, mnożnik ceny i kaucja kodu. Mnożnik = opakowanie kodu
     * przez opakowanie głównego EAN-u razy współczynnik ceny (puszka z czteropaka: 0,25; z sześciopaku 0,166 × 1,006).
     */
    private static String labelCodes(CollectorCatalogRepository.Article row, List<CollectorCatalogRepository.Code> rows,
            Map<Short, DepositKind> deposits) {
        long main = CollectorCatalogFile.number(row.getEan());
        BigDecimal mainPack = BigDecimal.ONE;
        for (CollectorCatalogRepository.Code code : rows) {
            if (CollectorCatalogFile.number(code.getEan()) == main && code.getPack() != null
                    && code.getPack().signum() > 0) {
                mainPack = code.getPack();
            }
        }
        List<String> entries = new ArrayList<>();
        for (CollectorCatalogRepository.Code code : rows) {
            long ean = CollectorCatalogFile.number(code.getEan());
            if (ean <= 0 || ean == main) {
                continue;
            }
            BigDecimal pack = code.getPack() == null || code.getPack().signum() <= 0 ? mainPack : code.getPack();
            BigDecimal factor = code.getFactor() == null || code.getFactor().signum() <= 0 ? BigDecimal.ONE
                    : code.getFactor();
            BigDecimal multiplier = pack.multiply(factor).divide(mainPack, 6, java.math.RoundingMode.HALF_UP);
            String deposit = deposit(deposits, code.getDepositCode());
            entries.add(ean + ";" + number(multiplier) + ";" + (deposit.isEmpty() ? ";" : deposit) + ";"
                    + part(code.getName()));
        }
        return String.join("|", entries);
    }

    /** Tekst w polu z separatorami: bez {@code |}, {@code ;} i tabulatorów. */
    private static String part(String value) {
        return value == null ? "" : value.replaceAll("[|;\\t\\r\\n]+", " ").strip();
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
