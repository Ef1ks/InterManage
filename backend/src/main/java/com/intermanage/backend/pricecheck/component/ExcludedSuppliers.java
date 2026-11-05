package com.intermanage.backend.pricecheck.component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Dostawcy, których towaru raporty weryfikujące zakup nie sprawdzają: zakup bazowy poza bazą, przepłacone artykuły i za
 * niskie marże. Kolporter to prasa: cena jest z góry ustalona i nie ma czego wyjaśniać. Raporty sprzedaży zostają bez
 * zmian. Kod dostawcy jest inny w każdym sklepie, więc rozpoznajemy go po NIP-ie z listy dostawców.
 */
@Component
public class ExcludedSuppliers {

    private static final List<String> NIPS = List.of("9591452482");

    private final SupplierRepository suppliers;

    public ExcludedSuppliers(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    /** Kody tych dostawców w sklepie, do porównania z dostawcą przy linii dokumentu. */
    public Set<String> codes(UUID storeId) {
        return Set.copyOf(suppliers.findCodesByNips(storeId, NIPS));
    }
}
