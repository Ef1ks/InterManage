package com.intermanage.backend.deliorder.entity;

import java.math.BigDecimal;

import com.intermanage.backend.product.entity.Product;

/** W czym zamawia się produkt z formularza garmażu. Nazwa stałej trafia do bazy. */
public enum OrderUnit {

    /** Na wagę, do 3 miejsc po przecinku (gramy). */
    KG(3),
    /** Całe sztuki. */
    SZT(0);

    private final int scale;

    OrderUnit(int scale) {
        this.scale = scale;
    }

    /** Domyślna jednostka zamawiania dla produktu z kartoteki: kg, gdy kartoteka podaje kg, inaczej sztuki. */
    public static OrderUnit defaultFor(Product product) {
        return product.getUnit() != null && product.getUnit().equalsIgnoreCase("kg") ? KG : SZT;
    }

    /** Czy ilość da się zamówić w tej jednostce (sztuki tylko całe, kg najwyżej z dokładnością do grama). */
    public boolean accepts(BigDecimal quantity) {
        return quantity.stripTrailingZeros().scale() <= scale;
    }
}
