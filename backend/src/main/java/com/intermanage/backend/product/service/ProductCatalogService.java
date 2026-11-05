package com.intermanage.backend.product.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.PriceControlFile;
import com.intermanage.backend.dataentry.repository.DataEntryPriceControlFileRepository;
import com.intermanage.backend.product.dto.ProductListItem;
import com.intermanage.backend.product.dto.ProductListPage;
import com.intermanage.backend.product.dto.ProductListRequest;
import com.intermanage.backend.product.dto.ProductPriceHistoryResponse;
import com.intermanage.backend.product.dto.ProductPriceHistoryResponse.PriceChange;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.entity.ProductPriceChange;
import com.intermanage.backend.product.mapper.ProductCatalogMapper;
import com.intermanage.backend.product.repository.PriceFileImportRepository;
import com.intermanage.backend.product.repository.ProductPriceChangeRepository;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.product.repository.ProductSearch;
import com.intermanage.backend.promotion.repository.PromotionImportRepository;

/**
 * Magazyn i produkty: kartoteka sklepu do przeglądania (lista z wyszukiwarką) i historia cen artykułu. Pozostałe dane
 * artykułu (stan, sprzedaż, dostawy) biorą się z raportów sprzedaży.
 */
@Service
public class ProductCatalogService {

    static final int PAGE_SIZE = 100;
    /** Ile zmian cen pokazujemy z każdego źródła. */
    static final int HISTORY_LIMIT = 200;
    /** Artykuł ma kilka EAN-ów, także z etykiet obniżek, a historia każdego idzie od początku 2026. */
    private static final int TILL_HISTORY_LIMIT = 1000;

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    /** Kolejność listy stała między stronami: po nazwie, przy tej samej nazwie po ITM8. */
    private static final Sort ORDER = ProductSearch.ORDER.and(Sort.by("itm8"));
    /** W linii pliku CTRLPRIX: EAN na 13 znakach i cena w groszach na 20 cyfrach. */
    private static final int EAN_LENGTH = 13;
    /** Kolumny sortowania z parametru {@code sort}; bez niego (albo "name") po nazwie, najpierw aktywne. */
    private static final Map<String, String> SORTS = Map.of("itm8", "itm8", "stock", "stock", "rotation",
            "rotation", "price", "salePrice");

    private final ProductRepository products;
    private final ProductCatalogMapper mapper;
    private final ProductPriceChangeRepository priceChanges;
    private final PriceFileImportRepository priceFiles;
    private final PromotionImportRepository promotions;
    private final DataEntryPriceControlFileRepository priceControlFiles;

    public ProductCatalogService(ProductRepository products, ProductCatalogMapper mapper,
            ProductPriceChangeRepository priceChanges, PriceFileImportRepository priceFiles,
            PromotionImportRepository promotions,
            DataEntryPriceControlFileRepository priceControlFiles) {
        this.products = products;
        this.mapper = mapper;
        this.priceChanges = priceChanges;
        this.priceFiles = priceFiles;
        this.promotions = promotions;
        this.priceControlFiles = priceControlFiles;
    }

    /**
     * Artykuły sklepu pasujące do wyszukiwania (każde słowo jako fragment nazwy, marki, ITM8 albo początek EAN-u, jak
     * {@link ProductSearch}) i filtrów; bez nich cała kartoteka. Domyślnie najpierw aktywne, alfabetycznie; przy
     * sortowaniu po stanie, rotacji albo cenie puste na końcu. Strony po {@link #PAGE_SIZE}.
     */
    @Transactional(readOnly = true)
    public ProductListPage list(UUID storeId, ProductListRequest request) {
        LocalDate today = LocalDate.now(STORE_ZONE);
        Specification<Product> spec = ProductSearch.inStoreMatching(storeId, ProductSearch.words(request.query()))
                .and(ProductSearch.withStock(request.stock(), request.stockFrom(), request.stockTo()))
                .and(ProductSearch.withRotation(request.rotationFrom(), request.rotationTo()));
        List<String> codes = request.nomenclatures() == null ? List.of() : request.nomenclatures().stream()
                .map(code -> code.replaceAll("\\D", ""))
                .filter(code -> !code.isEmpty() && code.length() <= 6)
                .toList();
        if (!codes.isEmpty()) {
            spec = spec.and(ProductSearch.inNomenclatures(codes));
        }
        if ("WITH".equals(request.cadencier()) || "WITHOUT".equals(request.cadencier())) {
            spec = spec.and(ProductSearch.inCadencier(storeId, "WITH".equals(request.cadencier())));
        }
        int page = Math.max(request.page() != null ? request.page() : 0, 0);
        Page<Product> found = products.findAll(spec, PageRequest.of(page, PAGE_SIZE, sort(request)));
        List<ProductListItem> items = found.getContent().stream()
                .map(product -> mapper.toListItem(product, today))
                .toList();
        return new ProductListPage(items, found.getTotalElements(), found.hasNext());
    }

    private static Sort sort(ProductListRequest request) {
        String property = request.sort() == null ? null : SORTS.get(request.sort());
        Sort.Direction direction = "desc".equals(request.direction()) ? Sort.Direction.DESC : Sort.Direction.ASC;
        if (property == null) {
            return direction == Sort.Direction.ASC ? ORDER
                    : Sort.by(Sort.Order.desc("active").nullsLast(), Sort.Order.desc("name"), Sort.Order.desc("itm8"));
        }
        return Sort.by(new Sort.Order(direction, property).nullsLast()).and(Sort.by("name", "itm8"));
    }

    /**
     * Przeceny (promocje z listy promocji) i zmiany ceny sprzedaży artykułu: z plików z kasy i importów kartoteki
     * (zapisywane od V70) oraz z plików zmiany cen z wprowadzania danych (po każdym EAN-ie artykułu).
     */
    @Transactional(readOnly = true)
    public ProductPriceHistoryResponse priceHistory(UUID storeId, String itm8) {
        Product product = products.findByStoreIdAndItm8(storeId, itm8)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego artykułu w kartotece sklepu"));

        List<ProductPriceHistoryResponse.Promotion> promotionList = promotions.findByArticle(storeId, itm8).stream()
                .map(row -> new ProductPriceHistoryResponse.Promotion(row.getStartDate(), row.getEndDate(),
                        row.getRegularSalePrice(), row.getPromoPrice(), row.getCatalogPurchasePrice(),
                        row.getMarginPercent(), row.getCancelled()))
                .toList();

        Set<String> codes = products.findAllEans(storeId, product.getId(), product.getItm8()).stream()
                .map(PriceControlFile::ean)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<PriceChange> changes = new ArrayList<>();
        priceFiles.findTillPriceChanges(storeId, itm8, codes.toArray(String[]::new), TILL_HISTORY_LIMIT)
                .forEach(row -> changes.add(new PriceChange(row.getEan(), row.getFileTime(),
                        ProductPriceChange.Source.TILL.name(), row.getPreviousSalePrice(), row.getSalePrice(),
                        row.getPreviousPromoPrice(), row.getPromoPrice(), row.getPromoFrom(), row.getPromoTo(),
                        row.getFileName(), null)));
        priceChanges.findByProduct(storeId, product.getId(), ProductPriceChange.Source.REPORT, Limit.of(HISTORY_LIMIT))
                .forEach(row -> changes.add(new PriceChange(null, row.getChangedAt(), row.getSource().name(),
                        row.getPreviousSalePrice(), row.getSalePrice(), row.getPreviousPromoPrice(),
                        row.getPromoPrice(), row.getPromoFrom(), row.getPromoTo(), row.getFileName(), null)));
        changes.addAll(dataEntryChanges(storeId, codes));
        changes.sort(Comparator.comparing(PriceChange::changedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new ProductPriceHistoryResponse(PriceControlFile.ean(product.getEan()), promotionList, changes);
    }

    /** Linie plików CTRLPRIX z którymś EAN-em artykułu; z pliku tylko nowa cena (poprzedniej w nim nie ma). */
    private List<PriceChange> dataEntryChanges(UUID storeId, Set<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        List<PriceChange> changes = new ArrayList<>();
        for (DataEntryPriceControlFileRepository.WithContent file : priceControlFiles.findContaining(storeId,
                codes.toArray(String[]::new), HISTORY_LIMIT)) {
            BigDecimal price = priceIn(file.getContent(), codes);
            if (price == null) {
                continue;
            }
            String supplier = file.getSupplierName() != null ? file.getSupplierName() : file.getSupplierCode();
            String description = file.getInvoiceNumber() != null && !file.getInvoiceNumber().isBlank()
                    ? supplier + " · faktura " + file.getInvoiceNumber() : supplier;
            changes.add(new PriceChange(null, LocalDateTime.ofInstant(file.getCreatedAt(), STORE_ZONE), "DATA_ENTRY",
                    null, price, null, null, null, null, description, file.getCreatedBy()));
        }
        return changes;
    }

    private static BigDecimal priceIn(byte[] content, Set<String> codes) {
        for (String line : new String(content, StandardCharsets.US_ASCII).split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.length() > EAN_LENGTH && codes.contains(trimmed.substring(0, EAN_LENGTH))) {
                String digits = trimmed.substring(EAN_LENGTH);
                if (digits.matches("\\d+")) {
                    return new BigDecimal(digits).movePointLeft(2);
                }
            }
        }
        return null;
    }
}
