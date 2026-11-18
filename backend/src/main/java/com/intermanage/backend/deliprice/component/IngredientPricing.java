package com.intermanage.backend.deliprice.component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.intermanage.backend.deliprice.entity.IngredientPrice;
import com.intermanage.backend.deliprice.entity.PriceSource;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.product.entity.Product;

/**
 * Aktualna cena składnika kalkulacji: z ostatniej dostawy (najnowsze przyjęcie towaru PZ z tym ITM8 w sklepie albo
 * z lokalnym ITM8 dostawcy i EAN-em artykułu),
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
        List<String> itm8s = products.stream().map(Product::getItm8).distinct().toList();
        Map<String, UUID> lastLineIds = new HashMap<>();
        documentLines.findLastPricedLines(storeId, itm8s)
                .forEach(line -> lastLineIds.put(line.getItm8(), line.getLineId()));
        Map<UUID, DocumentLine> lines = new HashMap<>();
        if (!lastLineIds.isEmpty()) {
            documentLines.findWithDocument(lastLineIds.values()).forEach(line -> lines.put(line.getId(), line));
        }
        Map<String, DocumentLine> lastDelivery = new HashMap<>();
        lastLineIds.forEach((itm8, lineId) -> {
            DocumentLine line = lines.get(lineId);
            if (line != null) {
                lastDelivery.put(itm8, line);
            }
        });
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
