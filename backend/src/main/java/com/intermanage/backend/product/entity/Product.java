package com.intermanage.backend.product.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.product.dto.PriceFileLine;
import com.intermanage.backend.product.dto.ProductReportRow;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Artykuł z kartoteki jednego sklepu w stanie z ostatniego importu, w którym wystąpił. Kluczem jest sklep + ITM8;
 * {@link #getEan()} to bieżący EAN, a pełna historia par ITM8 + EAN jest w {@link ProductEan}.
 * Znaczenie kolumn opisuje migracja V3__products.sql.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 13, updatable = false)
    private String itm8;

    @Column(nullable = false, length = 14)
    private String ean;

    private String name;
    private String brand;
    private String supplier;
    private String nomenclature;

    // Kody nomenklatury na trzech poziomach (rejon, rodzina, podrodzina), liczone przez bazę z nomenclature;
    // do łączenia z Nomenclature w zapytaniach. Po zapisie w tej samej sesji nieaktualne.
    @Column(name = "region_code", length = 2, insertable = false, updatable = false)
    private String regionCode;

    @Column(name = "family_code", length = 4, insertable = false, updatable = false)
    private String familyCode;

    @Column(name = "subfamily_code", length = 6, insertable = false, updatable = false)
    private String subfamilyCode;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "purchase_price", precision = 12, scale = 4)
    private BigDecimal purchasePrice;

    @Column(precision = 14, scale = 3)
    private BigDecimal stock;

    @Column(precision = 14, scale = 3)
    private BigDecimal rotation;

    @Column(name = "case_size", precision = 12, scale = 3)
    private BigDecimal caseSize;

    private Boolean active;

    @Column(name = "priority_ean")
    private Boolean priorityEan;

    @Column(name = "vat_code")
    private Short vatCode;

    private String unit;

    @Column(name = "net_content", precision = 12, scale = 3)
    private BigDecimal netContent;

    private Integer dlv;
    private Short rp;

    @Column(name = "deposit_code")
    private Short depositCode;

    private String reference;

    @Column(name = "till_code")
    private Short tillCode;

    @Column(name = "type_code")
    private Short typeCode;

    private String kind;

    @Column(name = "sale_unit_code")
    private Short saleUnitCode;

    @Column(name = "cn_code")
    private String cnCode;

    /** CENA_CAD: cena sprzedaży sztuki narzucona przez centralę (cadencier). */
    @Column(name = "cadencier_price", precision = 12, scale = 2)
    private BigDecimal cadencierPrice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_seen_import_id", nullable = false, updatable = false)
    private ProductImport firstSeenImport;

    /** Ostatni import, w którym produkt był w pliku. Starszy niż najnowszy import = zniknął z raportu. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_seen_import_id", nullable = false)
    private ProductImport lastSeenImport;

    /**
     * Średnia waga jednej sztuki w kg dla produktów liczonych na sztuki (ogórek, sałata). Wpisuje ją kalkulator cen
     * garmażu, bo kartoteka jej nie zna; import raportu jej nie zmienia.
     */
    @Column(name = "piece_weight", precision = 10, scale = 3)
    private BigDecimal pieceWeight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_weight_set_by")
    private AppUser pieceWeightSetBy;

    @Column(name = "piece_weight_set_at")
    private Instant pieceWeightSetAt;

    /**
     * Czas pliku TRACEART, z którego jest {@link #salePrice}; {@code null} = cena z raportu kartoteki. Raport nadpisuje
     * cenę tylko wtedy, gdy jest nowszy niż ten plik.
     */
    @Column(name = "sale_price_at")
    private LocalDateTime salePriceAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_file_id")
    private PriceFileImport priceFile;

    /** Cena promocyjna brutto z pliku TRACEART w okresie {@link #promoFrom}-{@link #promoTo}; cena sprzedaży to wtedy regularna. */
    @Column(name = "promo_price", precision = 12, scale = 2)
    private BigDecimal promoPrice;

    @Column(name = "promo_from")
    private LocalDate promoFrom;

    @Column(name = "promo_to")
    private LocalDate promoTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Ostatnia zmiana danych z pliku; samo ponowne wystąpienie w raporcie jej nie przesuwa. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
    }

    public Product(ProductReportRow row, ProductImport productImport) {
        this.store = productImport.getStore();
        this.itm8 = row.itm8();
        this.firstSeenImport = productImport;
        this.lastSeenImport = productImport;
        apply(row);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    /** Wiersz z kolejnego raportu. Zwraca {@code true}, gdy zmieniło się którekolwiek pole z pliku. */
    public boolean seenIn(ProductReportRow row, ProductImport productImport) {
        lastSeenImport = productImport;
        if (salePriceAt != null && !priceFileNewerThan(productImport.getReportGeneratedAt())) {
            // Raport jest nowszy niż ostatni plik TRACEART: od teraz obowiązuje jego cena.
            salePriceAt = null;
            priceFile = null;
        }
        if (sameDataAs(row)) {
            return false;
        }
        apply(row);
        updatedAt = Instant.now();
        return true;
    }

    /**
     * Artykułu nie ma w kolejnym raporcie: zostaje w kartotece, ale traci to, co obowiązuje tylko z ostatniego raportu:
     * CENA_CAD, stan i rotację, i nie jest już aktywny. Zwykle system sklepu przeniósł EAN na nowy ITM8 razem ze stanem,
     * więc stary stan na starej karcie liczyłby się drugi raz (ujemne stany, zerówki, magazyn). Gdy karta wróci do
     * raportu, {@link #seenIn} wpisze jej dane z pliku.
     */
    public void missingFrom(ProductImport productImport) {
        if (lastSeenImport == productImport) {
            return;
        }
        if (cadencierPrice != null || stock != null || rotation != null || !Boolean.FALSE.equals(active)) {
            cadencierPrice = null;
            stock = null;
            rotation = null;
            active = false;
            updatedAt = Instant.now();
        }
    }

    private boolean sameDataAs(ProductReportRow row) {
        return Objects.equals(ean, row.ean())
                && Objects.equals(name, row.name())
                && Objects.equals(brand, row.brand())
                && Objects.equals(supplier, row.supplier())
                && Objects.equals(nomenclature, row.nomenclature())
                && sameNumber(salePrice, reportSalePrice(row))
                && sameNumber(purchasePrice, row.purchasePrice())
                && sameNumber(stock, row.stock())
                && sameNumber(rotation, row.rotation())
                && sameNumber(caseSize, row.caseSize())
                && Objects.equals(active, row.active())
                && Objects.equals(priorityEan, row.priorityEan())
                && Objects.equals(vatCode, row.vatCode())
                && Objects.equals(unit, row.unit())
                && sameNumber(netContent, row.netContent())
                && Objects.equals(dlv, row.dlv())
                && Objects.equals(rp, row.rp())
                && Objects.equals(depositCode, row.depositCode())
                && Objects.equals(reference, row.reference())
                && Objects.equals(tillCode, row.tillCode())
                && Objects.equals(typeCode, row.typeCode())
                && Objects.equals(kind, row.kind())
                && Objects.equals(saleUnitCode, row.saleUnitCode())
                && Objects.equals(cnCode, row.cnCode())
                && sameNumber(cadencierPrice, row.cadencierPrice());
    }

    /** Cena z pliku TRACEART nowszego niż raport zostaje, inaczej cena z raportu. */
    private BigDecimal reportSalePrice(ProductReportRow row) {
        return salePriceAt != null ? salePrice : row.salePrice();
    }

    private boolean priceFileNewerThan(LocalDateTime reportGeneratedAt) {
        return reportGeneratedAt == null || salePriceAt.isAfter(reportGeneratedAt);
    }

    /** Co linia pliku TRACEART zrobiła z ceną artykułu. */
    public enum PriceFileResult {
        UPDATED, UNCHANGED, OLDER
    }

    /**
     * Cena z pliku TRACEART: obowiązuje, gdy plik nie jest starszy niż raport kartoteki, z którego jest bieżąca cena,
     * ani niż poprzedni plik. Linia bez promocji kończy wcześniejszą promocję.
     */
    public PriceFileResult applyPriceFile(PriceFileLine line, PriceFileImport file) {
        LocalDateTime current = salePriceAt != null ? salePriceAt : lastSeenImport.getReportGeneratedAt();
        if (current != null && file.getFileTime().isBefore(current)) {
            return PriceFileResult.OLDER;
        }
        boolean same = sameNumber(salePrice, line.salePrice())
                && sameNumber(promoPrice, line.promoPrice())
                && Objects.equals(promoFrom, line.promoFrom())
                && Objects.equals(promoTo, line.promoTo());
        salePriceAt = file.getFileTime();
        priceFile = file;
        if (same) {
            return PriceFileResult.UNCHANGED;
        }
        salePrice = line.salePrice();
        promoPrice = line.promoPrice();
        promoFrom = line.promoFrom();
        promoTo = line.promoTo();
        updatedAt = Instant.now();
        return PriceFileResult.UPDATED;
    }

    /** {@link BigDecimal#equals} rozróżnia 1.0 i 1.00, a liczy się wartość. */
    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private void apply(ProductReportRow row) {
        ean = row.ean();
        name = row.name();
        brand = row.brand();
        supplier = row.supplier();
        nomenclature = row.nomenclature();
        salePrice = reportSalePrice(row);
        purchasePrice = row.purchasePrice();
        stock = row.stock();
        rotation = row.rotation();
        caseSize = row.caseSize();
        active = row.active();
        priorityEan = row.priorityEan();
        vatCode = row.vatCode();
        unit = row.unit();
        netContent = row.netContent();
        dlv = row.dlv();
        rp = row.rp();
        depositCode = row.depositCode();
        reference = row.reference();
        tillCode = row.tillCode();
        typeCode = row.typeCode();
        kind = row.kind();
        saleUnitCode = row.saleUnitCode();
        cnCode = row.cnCode();
        cadencierPrice = row.cadencierPrice();
    }

    public UUID getId() {
        return id;
    }

    public String getItm8() {
        return itm8;
    }

    public String getEan() {
        return ean;
    }

    /** Kod kaucji z raportu kartoteki (KAUCJA): 60 szkło, 70 puszka, 80 PET, 1 butelka zwrotna, 0 bez kaucji. */
    public Short getDepositCode() {
        return depositCode;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public Store getStore() {
        return store;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public Boolean getActive() {
        return active;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public BigDecimal getNetContent() {
        return netContent;
    }

    public String getSupplier() {
        return supplier;
    }

    /** STAN z raportu kartoteki, może być ujemny. */
    public BigDecimal getStock() {
        return stock;
    }

    /** ROTACJA z raportu kartoteki. */
    public BigDecimal getRotation() {
        return rotation;
    }

    /** COLI: ile sztuk w kartonie. */
    public Short getVatCode() {
        return vatCode;
    }

    /** Stawka VAT z kodu z kartoteki (1 = 23%, 2 = 8%, 6 = 5%); inne kody są niepewne (np. 7 przy kaucjach). */
    public BigDecimal vatPercent() {
        return vatCode == null ? null : switch (vatCode) {
            case 1 -> BigDecimal.valueOf(23);
            case 2 -> BigDecimal.valueOf(8);
            case 6 -> BigDecimal.valueOf(5);
            default -> null;
        };
    }

    public String getCnCode() {
        return cnCode;
    }

    public BigDecimal getCaseSize() {
        return caseSize;
    }

    /** Ostatni import, w którym produkt był; z jego raportu są stan i rotacja. */
    public ProductImport getLastSeenImport() {
        return lastSeenImport;
    }

    /**
     * Jednostka, w której wpisuje się ilość produktu w przepisie. Artykuły wagowe ("A.Wa") są liczone na kilogramy,
     * reszta w jednostce z kartoteki (kg, L, szt.).
     */
    public String getCostUnit() {
        if (unit == null || unit.isBlank()) {
            return "szt.";
        }
        return "A.Wa".equalsIgnoreCase(unit) ? "kg" : unit;
    }

    /**
     * Cena zakupu jednej jednostki {@link #getCostUnit()}: cena zakupu z kartoteki dotyczy sprzedawanej sztuki
     * (np. mąka 1 kg, bita śmietana 0,055 kg), a WAGA mówi, ile jednostki w niej jest. Towar luzem ma WAGĘ 1.
     * {@code null}, gdy w kartotece nie ma ceny zakupu.
     */
    public BigDecimal getUnitCost() {
        return unitCostOf(purchasePrice);
    }

    /**
     * Cena jednostki {@link #getCostUnit()} z ceny za sprzedawaną sztukę - z kartoteki albo z linii dostawy PZ
     * (tam cena zakupu też dotyczy sztuki). {@code null}, gdy ceny nie ma.
     */
    public BigDecimal unitCostOf(BigDecimal pricePerItem) {
        if (pricePerItem == null || pricePerItem.signum() <= 0) {
            return null;
        }
        if (netContent == null || netContent.signum() <= 0) {
            return pricePerItem.setScale(4, RoundingMode.HALF_UP);
        }
        return pricePerItem.divide(netContent, 4, RoundingMode.HALF_UP);
    }

    /** Odwrotność {@link #unitCostOf}: cena sprzedawanej sztuki z kosztu jednostki (wiaderko 3 kg to koszt 1 kg razy 3). */
    public BigDecimal itemCostOf(BigDecimal unitCost) {
        return netContent == null || netContent.signum() <= 0 ? unitCost : unitCost.multiply(netContent);
    }

    /** Czy ilość w przepisie wpisuje się w sztukach - tylko wtedy ma sens {@link #getPieceWeight()}. */
    public boolean isCountedInPieces() {
        return "szt.".equals(getCostUnit());
    }

    /** CENA_CAD z raportu kartoteki; {@code null}, gdy centrala nie narzuca ceny. */
    public LocalDateTime getSalePriceAt() {
        return salePriceAt;
    }

    public BigDecimal getPromoPrice() {
        return promoPrice;
    }

    public LocalDate getPromoFrom() {
        return promoFrom;
    }

    public LocalDate getPromoTo() {
        return promoTo;
    }

    public BigDecimal getCadencierPrice() {
        return cadencierPrice;
    }

    public BigDecimal getPieceWeight() {
        return pieceWeight;
    }

    /** Nowa średnia waga sztuki albo jej wyczyszczenie ({@code null}); ta sama wartość nie zmienia autora. */
    public void setPieceWeight(BigDecimal weight, AppUser by) {
        if (weight == null ? pieceWeight == null : pieceWeight != null && weight.compareTo(pieceWeight) == 0) {
            return;
        }
        pieceWeight = weight;
        pieceWeightSetBy = by;
        pieceWeightSetAt = Instant.now();
    }

    /** NOM, np. "36 60 02": dwie pierwsze cyfry to dział (24, 36, 46 to wyroby garmażeryjne). */
    public String getNomenclature() {
        return nomenclature;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public String getFamilyCode() {
        return familyCode;
    }

    public String getSubfamilyCode() {
        return subfamilyCode;
    }
}
