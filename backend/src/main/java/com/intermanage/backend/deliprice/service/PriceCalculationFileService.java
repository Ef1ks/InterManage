package com.intermanage.backend.deliprice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.ProductFileRequest;
import com.intermanage.backend.dataentry.service.DataEntryProductFileService;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.deliprice.dto.PurchaseFileLine;
import com.intermanage.backend.deliprice.entity.PriceCalculation;
import com.intermanage.backend.deliprice.mapper.PriceCalculationMapper;
import com.intermanage.backend.deliprice.repository.PriceCalculationRepository;
import com.intermanage.backend.product.entity.Product;

/**
 * Plik CADBEZP z kalkulatora: jedyna zmiana to cena zakupu z kosztu kalkulacji. Wszystko inne idzie z kartoteki, także
 * cena sprzedaży z półki i dostawca (osobny plik na dostawcę), bo wczytany plik przepisuje artykuł z pliku i nie
 * może zmienić niczego więcej. Sama cena sprzedaży z kalkulacji idzie przez wprowadzanie danych i „Zmień ceny”.
 */
@Service
public class PriceCalculationFileService {

    /** Słowo w nazwie pliku w miejscu numeru faktury. */
    private static final String FILE_LABEL = "KALKULACJE";
    /** Kod stawki z kartoteki na procent, sprawdzone z liniami dokumentów (jak we wprowadzaniu danych). */
    private static final Map<Short, BigDecimal> VAT_BY_CODE = Map.of((short) 1, BigDecimal.valueOf(23),
            (short) 2, BigDecimal.valueOf(8), (short) 6, BigDecimal.valueOf(5));

    private final PriceCalculationRepository calculations;
    private final DataEntryProductFileService productFiles;
    private final PriceCalculationMapper mapper;

    public PriceCalculationFileService(PriceCalculationRepository calculations, DataEntryProductFileService productFiles,
            PriceCalculationMapper mapper) {
        this.calculations = calculations;
        this.productFiles = productFiles;
        this.mapper = mapper;
    }

    /** Kalkulacje, które mogą trafić do pliku, z obecną ceną zakupu i ceną z kalkulacji. */
    @Transactional(readOnly = true)
    public List<PurchaseFileLine> lines(UUID storeId) {
        return PriceCalculationService.sorted(calculations.findActiveWithItems(storeId)).stream()
                .filter(PriceCalculationFileService::hasCost)
                .map(calculation -> mapper.toPurchaseLine(calculation, purchasePrice(calculation)))
                .toList();
    }

    /** Plik jednego dostawcy; front wysyła kalkulacje po grupach z {@link #lines}. */
    @Transactional(readOnly = true)
    public DataEntryService.ExportFile file(UUID storeId, List<UUID> calculationIds) {
        Set<UUID> ids = new HashSet<>(calculationIds);
        List<PriceCalculation> selected = PriceCalculationService.sorted(calculations.findActiveWithItems(storeId))
                .stream()
                .filter(calculation -> ids.contains(calculation.getId()) && hasCost(calculation))
                .toList();
        if (selected.isEmpty()) {
            throw ApiException.badRequest("Wybierz kalkulacje z przepisem");
        }
        String supplier = selected.get(0).getProduct().getSupplier();
        return productFiles.file(storeId, new ProductFileRequest(null, null, supplier, FILE_LABEL,
                selected.stream().map(PriceCalculationFileService::row).toList()));
    }

    private static boolean hasCost(PriceCalculation calculation) {
        BigDecimal cost = calculation.getCostPerKg();
        return calculation.getProduct() != null && calculation.getItemCount() > 0 && cost != null && cost.signum() > 0;
    }

    /** Koszt kalkulacji jest na 1 kg, a kartoteka trzyma cenę sprzedawanej sztuki (wiaderko 3 kg to koszt razy 3). */
    private static BigDecimal purchasePrice(PriceCalculation calculation) {
        return calculation.getProduct().itemCostOf(calculation.getCostPerKg()).setScale(2, RoundingMode.HALF_UP);
    }

    private static ProductFileRequest.Row row(PriceCalculation calculation) {
        Product product = calculation.getProduct();
        return new ProductFileRequest.Row(product.getEan(), product.getName(), product.getBrand(), product.getCostUnit(),
                product.getNetContent(), purchasePrice(calculation), vatPercent(calculation), product.getSalePrice(),
                product.getSubfamilyCode(), null, product.getCnCode(), product.getCaseSize());
    }

    /** Stawka z kartoteki; artykuł bez znanego kodu stawki bierze ją z kalkulacji. */
    private static BigDecimal vatPercent(PriceCalculation calculation) {
        Short code = calculation.getProduct().getVatCode();
        BigDecimal percent = code == null ? null : VAT_BY_CODE.get(code);
        return percent != null ? percent : BigDecimal.valueOf(calculation.getVatRate());
    }
}
