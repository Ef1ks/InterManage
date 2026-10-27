package com.intermanage.backend.component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.intermanage.backend.entity.DocumentLine;
import com.intermanage.backend.entity.DocumentType;
import com.intermanage.backend.entity.IngredientPrice;
import com.intermanage.backend.entity.PriceSource;
import com.intermanage.backend.entity.Product;
import com.intermanage.backend.repository.DocumentLineRepository;

/**
 * Aktualna cena składnika kalkulacji: z ostatniej dostawy (najnowsze przyjęcie towaru PZ z tym ITM8 w sklepie),
 * a gdy produktu nie było w dostawach - z kartoteki. Cena zakupu w obu źródłach dotyczy sprzedawanej sztuki,
 * więc na kg / L przelicza ją {@link Product#unitCostOf}.
 */
@Component
public class IngredientPricing {

    private final DocumentLineRepository documentLines;

    public IngredientPricing(DocumentLineRepository documentLines) {
        this.documentLines = documentLines;
    }

    /** Ceny produktów jednego sklepu po id produktu, jednym zapytaniem o dostawy. */
    public Map<UUID, IngredientPrice> current(UUID storeId, Collection<Product> products) {
        Map<UUID, IngredientPrice> prices = new HashMap<>();
        if (products.isEmpty()) {
            return prices;
        }
        Map<String, DocumentLine> lastDelivery = new HashMap<>();
        List<String> itm8s = products.stream().map(Product::getItm8).distinct().toList();
        // Linie są od najnowszej, więc pierwsza dla ITM8 to ostatnia dostawa.
        documentLines.findPricedLines(storeId, DocumentType.PZ, itm8s)
                .forEach(line -> lastDelivery.putIfAbsent(line.getItm8(), line));
        for (Product product : products) {
            DocumentLine delivery = lastDelivery.get(product.getItm8());
            BigDecimal fromDelivery = delivery != null ? product.unitCostOf(delivery.getPurchasePrice()) : null;
            if (fromDelivery != null) {
                prices.put(product.getId(), new IngredientPrice(fromDelivery, PriceSource.DELIVERY, delivery.getDocument()));
            } else if (product.getUnitCost() != null) {
                prices.put(product.getId(), new IngredientPrice(product.getUnitCost(), PriceSource.CATALOG, null));
            } else {
                prices.put(product.getId(), IngredientPrice.NONE);
            }
        }
        return prices;
    }
}
