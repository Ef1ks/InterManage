package com.intermanage.backend.importguide.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.document.entity.DocumentImport;
import com.intermanage.backend.document.mapper.DocumentMapper;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.importguide.dto.ImportHistoryPage;
import com.intermanage.backend.nomenclature.entity.NomenclatureImport;
import com.intermanage.backend.nomenclature.mapper.NomenclatureMapper;
import com.intermanage.backend.nomenclature.repository.NomenclatureImportRepository;
import com.intermanage.backend.pricecheck.entity.CadencierImport;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository;
import com.intermanage.backend.product.entity.ProductCodeImport;
import com.intermanage.backend.product.entity.ProductImport;
import com.intermanage.backend.product.mapper.ProductImportMapper;
import com.intermanage.backend.product.repository.ProductCodeImportRepository;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.promotion.entity.PromotionImport;
import com.intermanage.backend.promotion.mapper.PromotionMapper;
import com.intermanage.backend.promotion.repository.PromotionImportRepository;
import com.intermanage.backend.sales.entity.SalesImport;
import com.intermanage.backend.sales.mapper.SalesMapper;
import com.intermanage.backend.sales.repository.SalesImportRepository;
import com.intermanage.backend.supplier.entity.SupplierImport;
import com.intermanage.backend.supplier.mapper.SupplierMapper;
import com.intermanage.backend.supplier.repository.SupplierImportRepository;

/**
 * Wspólna historia importów Importu danych: wgrania wszystkich raportów razem, od najnowszego, porcjami po
 * {@link #PAGE_SIZE}. Każdy raport daje swoje wgrania starsze niż kursor (najwyżej porcja + 1, żeby wiedzieć, czy
 * są dalsze), a porcja to najnowsze z nich.
 */
@Service
public class ImportHistoryService {

    static final int PAGE_SIZE = 30;

    /** Kursor pierwszej porcji: później niż każde wgranie, a wciąż w zakresie timestamptz PostgreSQL. */
    private static final Instant NEWEST = Instant.parse("9999-12-31T00:00:00Z");

    private final ProductImportRepository products;
    private final ProductCodeImportRepository productCodes;
    private final PromotionImportRepository promotions;
    private final SalesImportRepository sales;
    private final DocumentImportRepository documents;
    private final CadencierImportRepository cadencier;
    private final SupplierImportRepository suppliers;
    private final NomenclatureImportRepository nomenclatures;
    private final ProductImportMapper productMapper;
    private final PromotionMapper promotionMapper;
    private final SalesMapper salesMapper;
    private final DocumentMapper documentMapper;
    private final PriceCheckMapper priceCheckMapper;
    private final SupplierMapper supplierMapper;
    private final NomenclatureMapper nomenclatureMapper;

    public ImportHistoryService(ProductImportRepository products, ProductCodeImportRepository productCodes,
            PromotionImportRepository promotions, SalesImportRepository sales, DocumentImportRepository documents,
            CadencierImportRepository cadencier, SupplierImportRepository suppliers,
            NomenclatureImportRepository nomenclatures, ProductImportMapper productMapper,
            PromotionMapper promotionMapper, SalesMapper salesMapper, DocumentMapper documentMapper,
            PriceCheckMapper priceCheckMapper, SupplierMapper supplierMapper, NomenclatureMapper nomenclatureMapper) {
        this.products = products;
        this.productCodes = productCodes;
        this.promotions = promotions;
        this.sales = sales;
        this.documents = documents;
        this.cadencier = cadencier;
        this.suppliers = suppliers;
        this.nomenclatures = nomenclatures;
        this.productMapper = productMapper;
        this.promotionMapper = promotionMapper;
        this.salesMapper = salesMapper;
        this.documentMapper = documentMapper;
        this.priceCheckMapper = priceCheckMapper;
        this.supplierMapper = supplierMapper;
        this.nomenclatureMapper = nomenclatureMapper;
    }

    /** Porcja wgrań starszych niż {@code before}; bez kursora od najnowszego. */
    @Transactional(readOnly = true)
    public ImportHistoryPage page(UUID storeId, Instant before) {
        Instant cursor = before != null ? before : NEWEST;
        Limit limit = Limit.of(PAGE_SIZE + 1);
        List<ImportHistoryPage.Entry> entries = new ArrayList<>();
        add(entries, "products", products.findLatestBefore(storeId, cursor, limit),
                ProductImport::getImportedAt, productMapper::toResponse);
        add(entries, "productCodes", productCodes.findLatestBefore(storeId, cursor, limit),
                ProductCodeImport::getImportedAt, productMapper::toResponse);
        add(entries, "promotions", promotions.findLatestBefore(storeId, cursor, limit),
                PromotionImport::getImportedAt, promotionMapper::toResponse);
        add(entries, "sales", sales.findLatestBefore(storeId, cursor, limit),
                SalesImport::getImportedAt, salesMapper::toResponse);
        add(entries, "documents", documents.findLatestBefore(storeId, cursor, limit),
                DocumentImport::getImportedAt, documentMapper::toResponse);
        add(entries, "cadencier", cadencier.findLatestBefore(storeId, cursor, limit),
                CadencierImport::getImportedAt, priceCheckMapper::toResponse);
        add(entries, "suppliers", suppliers.findLatestBefore(storeId, cursor, limit),
                SupplierImport::getImportedAt, supplierMapper::toResponse);
        add(entries, "nomenclatures", nomenclatures.findLatestBefore(storeId, cursor, limit),
                NomenclatureImport::getImportedAt, nomenclatureMapper::toResponse);
        entries.sort(Comparator.comparing(ImportHistoryPage.Entry::importedAt).reversed());
        boolean hasMore = entries.size() > PAGE_SIZE;
        return new ImportHistoryPage(hasMore ? List.copyOf(entries.subList(0, PAGE_SIZE)) : entries, hasMore);
    }

    private static <T> void add(List<ImportHistoryPage.Entry> entries, String kind, List<T> imports,
            Function<T, Instant> importedAt, Function<T, ?> toResponse) {
        for (T item : imports) {
            entries.add(new ImportHistoryPage.Entry(kind, importedAt.apply(item), toResponse.apply(item)));
        }
    }
}
