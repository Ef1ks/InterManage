package com.intermanage.backend.deliprice.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Kalkulacja ceny gotowego wyrobu garmażeryjnego z kartoteki ({@link #getProduct()}): składniki z ilościami, na 1 kg
 * albo na cały przepis. Koszt 1 kg + {@value #MARKUP_PERCENT}% narzutu + VAT daje cenę wyliczoną, zaokrągloną w górę
 * do ,99 zł; zamiast niej można ustawić własną ({@link #getManualPrice()}). Ceny składników są zapamiętane
 * ({@link PriceCalculationItem}). Usunięcie to znacznik.
 */
@Entity
@Table(name = "price_calculations")
public class PriceCalculation {

    /** Narzut na koszt składników. */
    public static final int MARKUP_PERCENT = 60;
    public static final short DEFAULT_VAT_RATE = 5;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal CENT = new BigDecimal("0.01");

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    /** Gotowy wyrób, którego cenę liczymy; pusty tylko w kalkulacjach sprzed powiązania z kartoteką. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /** Nazwa wyrobu z kartoteki w chwili zapisu. */
    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CalculationMode mode;

    /** Ile kg wyrobu wychodzi z przepisu (tylko {@link CalculationMode#PER_BATCH}). */
    @Column(name = "batch_yield", precision = 12, scale = 3)
    private BigDecimal batchYield;

    @Column(name = "vat_rate", nullable = false)
    private short vatRate = DEFAULT_VAT_RATE;

    @Column(name = "prices_refreshed_at")
    private Instant pricesRefreshedAt;

    /** Własna cena sprzedaży 1 kg zamiast wyliczonej; idzie do eksportu. */
    @Column(name = "manual_price", precision = 10, scale = 2)
    private BigDecimal manualPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manual_price_set_by")
    private AppUser manualPriceSetBy;

    @Column(name = "manual_price_set_at")
    private Instant manualPriceSetAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "calculation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<PriceCalculationItem> items = new ArrayList<>();

    protected PriceCalculation() {
    }

    public PriceCalculation(Store store, AppUser createdBy) {
        this.store = store;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        pricesRefreshedAt = createdAt;
    }

    public record Ingredient(Product product, BigDecimal quantity) {
    }

    /** Ceny składników, które już są w kalkulacji - przy zapisie zostają, nowe składniki dostają aktualną cenę. */
    public Map<UUID, IngredientPrice> currentPrices() {
        Map<UUID, IngredientPrice> prices = new HashMap<>();
        items.forEach(item -> prices.put(item.getProduct().getId(), item.price()));
        return prices;
    }

    /**
     * Nowa treść z kreatora; składniki zastępują poprzednie, w podanej kolejności. {@code prices} to ceny po id
     * produktu (zapamiętane dla znanych składników, aktualne dla nowych). Stare składniki trzeba wcześniej usunąć
     * ({@link #clearItems}) i zapisać, bo ten sam produkt może być w kalkulacji tylko raz.
     */
    public void update(Product product, CalculationMode mode, BigDecimal batchYield, short vatRate,
            List<Ingredient> ingredients, Map<UUID, IngredientPrice> prices, AppUser by) {
        this.product = product;
        this.name = product.getName() != null ? product.getName() : "ITM8 " + product.getItm8();
        this.mode = mode;
        this.batchYield = mode == CalculationMode.PER_BATCH ? batchYield : null;
        this.vatRate = vatRate;
        items.clear();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            items.add(new PriceCalculationItem(this, ingredient.product(), i, ingredient.quantity(),
                    prices.getOrDefault(ingredient.product().getId(), IngredientPrice.NONE)));
        }
        updatedBy = by;
        updatedAt = Instant.now();
    }

    public void clearItems() {
        items.clear();
    }

    /** Własna cena sprzedaży albo powrót do wyliczonej ({@code null}); ta sama cena nie zmienia autora. */
    public void setManualPrice(BigDecimal price, AppUser by) {
        if (price == null ? manualPrice == null : manualPrice != null && price.compareTo(manualPrice) == 0) {
            return;
        }
        manualPrice = price;
        manualPriceSetBy = price == null ? null : by;
        manualPriceSetAt = price == null ? null : Instant.now();
    }

    /** "Odśwież ceny": każdy składnik dostaje aktualną cenę (ostatnia dostawa albo kartoteka). */
    public void refreshPrices(Function<Product, IngredientPrice> prices) {
        items.forEach(item -> item.setPrice(prices.apply(item.getProduct())));
        pricesRefreshedAt = Instant.now();
    }

    /** Suma kosztów składników, które mają cenę. */
    public BigDecimal getTotalCost() {
        return rawTotal().setScale(2, RoundingMode.HALF_UP);
    }

    /** Koszt 1 kg wyrobu: przy składzie na 1 kg to suma, przy całym przepisie suma podzielona przez wydajność. */
    public BigDecimal getCostPerKg() {
        BigDecimal perKg = rawCostPerKg();
        return perKg == null ? null : perKg.setScale(2, RoundingMode.HALF_UP);
    }

    /** Cena sprzedaży 1 kg do eksportu: własna, a bez niej wyliczona. */
    public BigDecimal getSalePrice() {
        return manualPrice != null ? manualPrice : getCalculatedPrice();
    }

    /**
     * Wyliczona cena sprzedaży brutto 1 kg: koszt + {@value #MARKUP_PERCENT}% narzutu + VAT, zaokrąglona w górę
     * do ,99 zł (15,40 -> 15,99; 16,00 -> 16,99). Pusta, gdy nie ma kosztu.
     */
    public BigDecimal getCalculatedPrice() {
        return calculatedPrice(rawCostPerKg());
    }

    /** Wyliczona cena, gdyby składniki miały podane ceny - podgląd "Odśwież ceny" bez zmiany kalkulacji. */
    public BigDecimal calculatedPriceWith(Function<Product, IngredientPrice> prices) {
        BigDecimal total = items.stream()
                .map(item -> {
                    BigDecimal unitCost = prices.apply(item.getProduct()).unitCost();
                    return unitCost == null ? null : unitCost.multiply(item.getQuantity());
                })
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return calculatedPrice(perKg(total));
    }

    private BigDecimal calculatedPrice(BigDecimal perKg) {
        if (perKg == null || perKg.signum() <= 0) {
            return null;
        }
        BigDecimal gross = perKg
                .multiply(BigDecimal.valueOf(100 + MARKUP_PERCENT)).divide(HUNDRED)
                .multiply(BigDecimal.valueOf(100 + vatRate)).divide(HUNDRED);
        BigDecimal price = gross.setScale(0, RoundingMode.CEILING).subtract(CENT);
        return price.compareTo(gross) < 0 ? price.add(BigDecimal.ONE) : price;
    }

    /** Składniki bez ceny - koszt jest wtedy zaniżony. */
    public int getMissingPriceCount() {
        return (int) items.stream().filter(item -> item.getUnitCost() == null).count();
    }

    private BigDecimal rawCostPerKg() {
        return perKg(rawTotal());
    }

    private BigDecimal perKg(BigDecimal total) {
        if (mode == CalculationMode.PER_BATCH) {
            return batchYield == null || batchYield.signum() <= 0 ? null
                    : total.divide(batchYield, 6, RoundingMode.HALF_UP);
        }
        return total;
    }

    private BigDecimal rawTotal() {
        return items.stream().map(PriceCalculationItem::getCost).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public Product getProduct() {
        return product;
    }

    public String getName() {
        return name;
    }

    public CalculationMode getMode() {
        return mode;
    }

    public BigDecimal getBatchYield() {
        return batchYield;
    }

    public short getVatRate() {
        return vatRate;
    }

    public Instant getPricesRefreshedAt() {
        return pricesRefreshedAt;
    }

    public BigDecimal getManualPrice() {
        return manualPrice;
    }

    public AppUser getManualPriceSetBy() {
        return manualPriceSetBy;
    }

    public Instant getManualPriceSetAt() {
        return manualPriceSetAt;
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public List<PriceCalculationItem> getItems() {
        return items;
    }

    public int getItemCount() {
        return items.size();
    }
}
