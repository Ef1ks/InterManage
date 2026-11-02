package com.intermanage.backend.dataentry.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.SupplierSalePriceRequest;
import com.intermanage.backend.dataentry.dto.SupplierSalePriceResponse;
import com.intermanage.backend.dataentry.entity.SupplierSalePrice;
import com.intermanage.backend.dataentry.mapper.SupplierSalePriceMapper;
import com.intermanage.backend.dataentry.repository.SupplierSalePriceRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Stałe ceny sprzedaży dostawców (Ustawienia sklepu): wszystkie artykuły od dostawcy sklep sprzedaje za jedną cenę
 * brutto, którą wprowadzanie danych podaje jako cenę sugerowaną. Każda zmiana zwraca całą listę sklepu.
 */
@Service
public class SupplierSalePriceService {

    private static final Comparator<SupplierSalePriceResponse> BY_NAME =
            Comparator.comparing(SupplierSalePriceResponse::supplierName, String.CASE_INSENSITIVE_ORDER);

    private final SupplierSalePriceRepository prices;
    private final SupplierRepository suppliers;
    private final AppUserRepository users;
    private final SupplierSalePriceMapper mapper;

    public SupplierSalePriceService(SupplierSalePriceRepository prices, SupplierRepository suppliers,
            AppUserRepository users, SupplierSalePriceMapper mapper) {
        this.prices = prices;
        this.suppliers = suppliers;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<SupplierSalePriceResponse> list(UUID storeId) {
        return prices.findByStoreIdAndDeletedAtIsNull(storeId).stream().map(mapper::toResponse).sorted(BY_NAME)
                .toList();
    }

    /** Ustawia cenę dostawcy; poprzednia dostaje znacznik. Ta sama cena niczego nie zapisuje. */
    @Transactional
    public List<SupplierSalePriceResponse> save(UUID storeId, UUID supplierId, SupplierSalePriceRequest request,
            UUID userId) {
        Supplier supplier = supplier(storeId, supplierId);
        SupplierSalePrice current = prices.findBySupplierIdAndDeletedAtIsNull(supplierId).orElse(null);
        if (current != null && current.getSalePrice().compareTo(request.salePrice()) == 0) {
            return list(storeId);
        }
        AppUser by = userId != null ? users.getReferenceById(userId) : null;
        if (current != null) {
            current.delete(by);
            // Znacznik przed nowym wpisem: aktywny może być tylko jeden na dostawcę (indeks unikalny).
            prices.flush();
        }
        prices.saveAndFlush(new SupplierSalePrice(supplier.getStore(), supplier, request.salePrice(), by));
        return list(storeId);
    }

    /** Usuwa cenę dostawcy (znacznik); „Przywróć” ustawia ją ponownie. */
    @Transactional
    public List<SupplierSalePriceResponse> delete(UUID storeId, UUID supplierId, UUID userId) {
        supplier(storeId, supplierId);
        SupplierSalePrice current = prices.findBySupplierIdAndDeletedAtIsNull(supplierId)
                .orElseThrow(() -> ApiException.notFound("Dostawca nie ma stałej ceny"));
        current.delete(userId != null ? users.getReferenceById(userId) : null);
        prices.flush();
        return list(storeId);
    }

    private Supplier supplier(UUID storeId, UUID supplierId) {
        return suppliers.findInStore(storeId, supplierId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
    }
}
