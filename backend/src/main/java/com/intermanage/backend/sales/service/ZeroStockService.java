package com.intermanage.backend.sales.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.pricecheck.entity.CadencierImport;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.product.repository.ProductSearch;
import com.intermanage.backend.sales.component.ZeroStockPdf;
import com.intermanage.backend.sales.dto.ZeroStockReport;
import com.intermanage.backend.sales.dto.ZeroStockReport.Group;
import com.intermanage.backend.sales.mapper.SalesMapper;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Raport zerówek: artykuły z bieżącej listy cadencier, aktywne w kartotece, ze stanem 0 (albo bez stanu), w wybranych
 * rejonach, rodzinach i podrodzinach. Ujemny stan nie jest zerówką: to błąd stanu, a towar zwykle leży na półce.
 */
@Service
@Transactional(readOnly = true)
public class ZeroStockService {

    private static final Sort NOMENCLATURE_ORDER = Sort.by("subfamilyCode", "name", "itm8");
    private static final int FAMILY_CODE_LENGTH = 4;

    private final ProductRepository products;
    private final ProductImportRepository productImports;
    private final CadencierImportRepository cadencierImports;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final SalesReportService salesReports;
    private final SalesMapper mapper;
    private final ZeroStockPdf reportPdf;

    public ZeroStockService(ProductRepository products, ProductImportRepository productImports,
            CadencierImportRepository cadencierImports, StoreRepository stores, AppUserRepository users,
            SalesReportService salesReports, SalesMapper mapper, ZeroStockPdf reportPdf) {
        this.products = products;
        this.productImports = productImports;
        this.cadencierImports = cadencierImports;
        this.stores = stores;
        this.users = users;
        this.salesReports = salesReports;
        this.mapper = mapper;
        this.reportPdf = reportPdf;
    }

    public ZeroStockReport report(UUID storeId, List<String> nomenclatures) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        Map<String, NomenclatureOption> options = salesReports.nomenclatures(storeId).stream()
                .collect(Collectors.toMap(NomenclatureOption::code, Function.identity()));
        List<Product> articles = nomenclatures.isEmpty() ? List.of() : products.findAll(
                ProductSearch.activeInStore(storeId)
                        .and(ProductSearch.inCadencier(storeId, true))
                        .and(ProductSearch.withStock("ZERO", null, null))
                        .and(ProductSearch.inNomenclatures(nomenclatures)),
                NOMENCLATURE_ORDER);

        Map<String, List<ZeroStockReport.Item>> families = new LinkedHashMap<>();
        for (Product article : articles) {
            families.computeIfAbsent(article.getSubfamilyCode().substring(0, FAMILY_CODE_LENGTH), code -> new ArrayList<>())
                    .add(mapper.toZeroStockItem(article));
        }
        List<Group> groups = families.entrySet().stream()
                .map(family -> new Group(family.getKey(), name(options, family.getKey()), family.getValue()))
                .toList();

        LocalDateTime stockGeneratedAt = productImports.findNewestReportGeneratedAt(storeId).orElse(null);
        LocalDateTime cadencierGeneratedAt = cadencierImports.findNewest(storeId)
                .map(CadencierImport::getReportGeneratedAt).orElse(null);
        return new ZeroStockReport(store.getNumber(), store.getName(), stockGeneratedAt, cadencierGeneratedAt,
                nomenclatures.stream().map(options::get).filter(option -> option != null).toList(),
                articles.size(), groups);
    }

    public ExportFile pdf(UUID storeId, List<String> nomenclatures, UUID userId) {
        ZeroStockReport report = report(storeId, nomenclatures);
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        return new ExportFile("zerowki-" + report.storeNumber() + ".pdf", "application/pdf",
                reportPdf.pdf(report, printedBy));
    }

    /** Artykuł może mieć kod spoza wgranej nomenklatury: wtedy zostaje sam kod. */
    private static String name(Map<String, NomenclatureOption> options, String code) {
        NomenclatureOption option = options.get(code);
        return option != null ? option.name() : null;
    }
}
