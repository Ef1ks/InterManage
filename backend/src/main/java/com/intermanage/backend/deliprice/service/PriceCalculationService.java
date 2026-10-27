package com.intermanage.backend.deliprice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.deliorder.service.DeliOrderFormService;
import com.intermanage.backend.deliprice.component.IngredientPricing;
import com.intermanage.backend.deliprice.dto.FinishedProductResponse;
import com.intermanage.backend.deliprice.dto.PriceCalculationRequest;
import com.intermanage.backend.deliprice.dto.PriceCalculationResponse;
import com.intermanage.backend.deliprice.dto.PriceCalculationSummary;
import com.intermanage.backend.deliprice.dto.PriceChangeResponse;
import com.intermanage.backend.deliprice.dto.PriceProductResponse;
import com.intermanage.backend.deliprice.dto.RefreshPricesRequest;
import com.intermanage.backend.deliprice.entity.CalculationMode;
import com.intermanage.backend.deliprice.entity.IngredientPrice;
import com.intermanage.backend.deliprice.entity.PriceCalculation;
import com.intermanage.backend.deliprice.entity.PriceCalculationItem;
import com.intermanage.backend.deliprice.mapper.PriceCalculationMapper;
import com.intermanage.backend.deliprice.repository.PriceCalculationRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.product.repository.ProductSearch;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;


/**
 * Kalkulator cen garmażu: przepisy na gotowe wyroby z kartoteki (nomenklatury {@link #FINISHED_NOMENCLATURES}).
 * Ceny składników są zapamiętywane przy dodaniu - z ostatniej dostawy PZ, a gdy jej nie ma, z kartoteki - i zmienia
 * je dopiero "Odśwież ceny" (podgląd, a potem zatwierdzenie z decyzjami - patrz {@link #needsDecision}). Z kosztu 1 kg
 * wychodzi cena sprzedaży (narzut, VAT, zaokrąglenie do ,99), którą można zastąpić własną; do Excela z EAN-em idzie
 * własna, a bez niej wyliczona. Usunięcie to znacznik.
 */
@Service
public class PriceCalculationService {

    /** Działy wyrobów garmażeryjnych w kartotece (dwie pierwsze cyfry NOM). */
    public static final List<String> FINISHED_NOMENCLATURES = List.of("24", "36", "46");
    private static final Set<Short> VAT_RATES = Set.of((short) 0, (short) 5, (short) 8, (short) 23);
    private static final int SEARCH_LIMIT = 30;
    private static final Collator POLISH = Collator.getInstance(Locale.of("pl", "PL"));
    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    /** Zmiana wyliczonej ceny, od której "Odśwież ceny" pyta, choć nikt nie ustawiał własnej ceny. */
    public static final BigDecimal PRICE_CHANGE_THRESHOLD_PERCENT = BigDecimal.TEN;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PriceCalculationRepository calculations;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final IngredientPricing pricing;
    private final XlsxWriter xlsxWriter;
    private final PriceCalculationMapper mapper;

    public PriceCalculationService(PriceCalculationRepository calculations, ProductRepository products,
            StoreRepository stores, AppUserRepository users, IngredientPricing pricing, XlsxWriter xlsxWriter,
            PriceCalculationMapper mapper) {
        this.calculations = calculations;
        this.products = products;
        this.stores = stores;
        this.users = users;
        this.pricing = pricing;
        this.xlsxWriter = xlsxWriter;
        this.mapper = mapper;
    }

    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    /** Wszystkie kalkulacje sklepu po nazwie; jest ich najwyżej kilkaset, więc frontend sam je filtruje. */
    @Transactional(readOnly = true)
    public List<PriceCalculationSummary> list(UUID storeId) {
        return sorted(calculations.findActiveWithItems(storeId)).stream().map(mapper::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public PriceCalculationResponse get(UUID storeId, UUID id) {
        return mapper.toResponse(active(storeId, id));
    }

    /** Gotowe wyroby z kartoteki do wyboru (wszystkie z działów garmażu, kilkaset), z informacją o kalkulacji. */
    @Transactional(readOnly = true)
    public List<FinishedProductResponse> finishedProducts(UUID storeId) {
        Map<UUID, UUID> calculationByProduct = new HashMap<>();
        calculations.findActiveWithItems(storeId).stream().filter(calculation -> calculation.getProduct() != null)
                .forEach(calculation -> calculationByProduct.put(calculation.getProduct().getId(), calculation.getId()));
        return products.findBy(ProductSearch.inStoreWithNomenclature(storeId, FINISHED_NOMENCLATURES),
                        q -> q.sortBy(ProductSearch.ORDER).all())
                .stream()
                .map(product -> mapper.toFinished(product, calculationByProduct.get(product.getId())))
                .toList();
    }

    @Transactional
    public PriceCalculationResponse create(UUID storeId, PriceCalculationRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        AppUser user = user(userId);
        PriceCalculation calculation = new PriceCalculation(store, user);
        apply(storeId, calculation, request, Map.of(), user);
        return mapper.toResponse(calculations.saveAndFlush(calculation));
    }

    @Transactional
    public PriceCalculationResponse update(UUID storeId, UUID id, PriceCalculationRequest request, UUID userId) {
        PriceCalculation calculation = active(storeId, id);
        Map<UUID, IngredientPrice> known = calculation.currentPrices();
        // Stare składniki znikają przed dodaniem nowych: Hibernate wstawia wiersze przed usuwaniem, a ten sam
        // produkt w kalkulacji może wystąpić tylko raz.
        calculation.clearItems();
        calculations.flush();
        apply(storeId, calculation, request, known, user(userId));
        return mapper.toResponse(calculations.saveAndFlush(calculation));
    }

    /**
     * Podgląd "Odśwież ceny" bez zmian w bazie: jak zmieni się wyliczona cena każdej kalkulacji (jednej albo
     * wszystkich, gdy {@code calculationId} pusty) i które wymagają decyzji.
     */
    @Transactional(readOnly = true)
    public List<PriceChangeResponse> previewRefresh(UUID storeId, UUID calculationId) {
        List<PriceCalculation> selected = sorted(selected(storeId, calculationId));
        Map<UUID, IngredientPrice> prices = currentPrices(storeId, selected);
        return selected.stream().map(calculation -> {
            BigDecimal newPrice = calculation.calculatedPriceWith(price(prices));
            return mapper.toChange(calculation, newPrice, changePercent(calculation.getCalculatedPrice(), newPrice),
                    needsDecision(calculation, newPrice));
        }).toList();
    }

    /**
     * "Odśwież ceny" po podglądzie: wszystkie składniki dostają aktualną cenę. Kalkulacje, które wymagają decyzji,
     * muszą ją mieć w {@code request}: KEEP zostawia cenę sprzed odświeżenia (wyliczona zostaje zapisana jako własna),
     * ACCEPT bierze nową wyliczoną. Gdy ceny zmieniły się od podglądu i decyzji brakuje - konflikt, podgląd od nowa.
     */
    @Transactional
    public List<PriceCalculationSummary> refresh(UUID storeId, RefreshPricesRequest request, UUID userId) {
        List<PriceCalculation> selected = selected(storeId, request.calculationId());
        Map<UUID, IngredientPrice> prices = currentPrices(storeId, selected);
        Map<UUID, RefreshPricesRequest.Choice> choices = new HashMap<>();
        request.decisions().forEach(decision -> choices.put(decision.calculationId(), decision.choice()));
        AppUser user = user(userId);

        for (PriceCalculation calculation : selected) {
            if (needsDecision(calculation, calculation.calculatedPriceWith(price(prices)))
                    && !choices.containsKey(calculation.getId())) {
                throw ApiException.conflict("Ceny zmieniły się od podglądu. Kliknij \u201eOdśwież ceny\u201d jeszcze raz.");
            }
        }
        for (PriceCalculation calculation : selected) {
            BigDecimal before = calculation.getSalePrice();
            calculation.refreshPrices(price(prices));
            RefreshPricesRequest.Choice choice = choices.get(calculation.getId());
            if (choice == RefreshPricesRequest.Choice.ACCEPT) {
                calculation.setManualPrice(null, user);
            } else if (choice == RefreshPricesRequest.Choice.KEEP && before != null) {
                calculation.setManualPrice(before, user);
            }
        }
        return sorted(selected).stream().map(mapper::toSummary).toList();
    }

    @Transactional
    public void delete(UUID storeId, UUID id, UUID userId) {
        active(storeId, id).markDeleted(user(userId));
    }

    @Transactional
    public PriceCalculationResponse restore(UUID storeId, UUID id) {
        PriceCalculation calculation = calculations.findInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kalkulacji"));
        if (calculation.getDeletedAt() != null && calculation.getProduct() != null
                && calculations.existsActiveForProduct(storeId, calculation.getProduct().getId())) {
            throw ApiException.conflict("Ten wyrób ma już inną kalkulację");
        }
        calculation.restore();
        return mapper.toResponse(calculation);
    }

    /** Ceny sprzedaży do Excela: EAN, nazwa, cena brutto i VAT - kalkulacje z wyrobem i policzoną ceną. */
    @Transactional(readOnly = true)
    public ExportFile export(UUID storeId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        List<List<Object>> rows = new ArrayList<>();
        for (PriceCalculation calculation : sorted(calculations.findActiveWithItems(storeId))) {
            BigDecimal salePrice = calculation.getSalePrice();
            if (calculation.getProduct() != null && salePrice != null) {
                rows.add(List.of(calculation.getProduct().getEan(), calculation.getName(), salePrice,
                        BigDecimal.valueOf(calculation.getVatRate())));
            }
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Nie ma jeszcze żadnej kalkulacji z ceną sprzedaży do eksportu");
        }
        byte[] content = xlsxWriter.write("Ceny garmażu", List.of(
                new XlsxWriter.Column("EAN", 16),
                new XlsxWriter.Column("Nazwa", 42),
                new XlsxWriter.Column("Cena sprzedaży", 14),
                new XlsxWriter.Column("VAT %", 8)), rows);
        return new ExportFile("ceny-garmazu-" + store.getNumber() + "-" + LocalDate.now(STORE_ZONE) + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    /** Składniki z kartoteki sklepu z aktualną ceną (ostatnia dostawa albo kartoteka). */
    @Transactional(readOnly = true)
    public List<PriceProductResponse> searchProducts(UUID storeId, String query) {
        List<String> words = ProductSearch.words(query);
        if (String.join("", words).length() < DeliOrderFormService.MIN_SEARCH_LENGTH) {
            throw ApiException.badRequest("Wpisz co najmniej " + DeliOrderFormService.MIN_SEARCH_LENGTH + " znaki");
        }
        List<Product> found = products.findBy(ProductSearch.inStoreMatching(storeId, words),
                q -> q.sortBy(ProductSearch.ORDER).limit(SEARCH_LIMIT).all());
        Map<UUID, IngredientPrice> prices = pricing.current(storeId, found);
        return found.stream().map(product -> mapper.toSearchResult(product, prices.get(product.getId()))).toList();
    }

    private List<PriceCalculation> selected(UUID storeId, UUID calculationId) {
        return calculationId != null ? List.of(active(storeId, calculationId))
                : calculations.findActiveWithItems(storeId);
    }

    private Map<UUID, IngredientPrice> currentPrices(UUID storeId, List<PriceCalculation> selected) {
        List<Product> ingredients = selected.stream().flatMap(calculation -> calculation.getItems().stream())
                .map(PriceCalculationItem::getProduct).distinct().toList();
        return pricing.current(storeId, ingredients);
    }

    private static Function<Product, IngredientPrice> price(Map<UUID, IngredientPrice> prices) {
        return product -> prices.getOrDefault(product.getId(), IngredientPrice.NONE);
    }

    /**
     * Czy przy odświeżeniu zapytać: kalkulacja z własną ceną, gdy wyliczona wychodzi inna niż dotąd (jest nowa cena
     * do rozważenia), albo cena wyliczona, która zmienia się o {@link #PRICE_CHANGE_THRESHOLD_PERCENT}% i więcej.
     */
    static boolean needsDecision(PriceCalculation calculation, BigDecimal newPrice) {
        BigDecimal previous = calculation.getCalculatedPrice();
        if (calculation.getManualPrice() != null) {
            return previous == null ? newPrice != null : newPrice == null || previous.compareTo(newPrice) != 0;
        }
        BigDecimal change = changePercent(previous, newPrice);
        return change != null && change.abs().compareTo(PRICE_CHANGE_THRESHOLD_PERCENT) >= 0;
    }

    /** Zmiana w procentach (+12,5 / -3,1); pusta, gdy którejś ceny nie ma. */
    private static BigDecimal changePercent(BigDecimal previous, BigDecimal next) {
        if (previous == null || next == null || previous.signum() == 0) {
            return null;
        }
        return next.subtract(previous).multiply(HUNDRED).divide(previous, 1, RoundingMode.HALF_UP);
    }

    private void apply(UUID storeId, PriceCalculation calculation, PriceCalculationRequest request,
            Map<UUID, IngredientPrice> known, AppUser user) {
        if (request.mode() == CalculationMode.PER_BATCH && request.batchYield() == null) {
            throw ApiException.badRequest("Podaj, ile kg wychodzi z przepisu")
                    .withProperty("errors", Map.of("batchYield", "Podaj, ile kg wychodzi z przepisu"));
        }
        if (!VAT_RATES.contains(request.vatRate())) {
            throw ApiException.badRequest("Stawka VAT to 0, 5, 8 albo 23%");
        }
        Product finished = products.findByIdAndStoreId(request.productId(), storeId)
                .orElseThrow(() -> ApiException.badRequest("Wybierz wyrób z kartoteki tego sklepu"));
        if (!isFinished(finished)) {
            throw ApiException.badRequest("Kalkulację robimy dla wyrobów z działów garmażu (nomenklatury 24, 36, 46)");
        }
        if (calculations.existsActiveForProductExcept(storeId, finished.getId(), calculation.getId())) {
            throw ApiException.conflict(finished.getName() + " ma już kalkulację - otwórz ją z listy");
        }

        Set<UUID> ids = new HashSet<>();
        for (PriceCalculationRequest.Item item : request.items()) {
            if (!ids.add(item.productId())) {
                throw ApiException.badRequest("Każdy produkt może być w przepisie tylko raz");
            }
        }
        Map<UUID, Product> found = products.findByIdInAndStoreId(ids, storeId).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (found.size() != ids.size()) {
            throw ApiException.badRequest("Część produktów nie należy do kartoteki tego sklepu");
        }
        // Znane składniki zachowują zapamiętaną cenę, nowe dostają aktualną.
        List<Product> added = found.values().stream().filter(product -> !known.containsKey(product.getId())).toList();
        Map<UUID, IngredientPrice> prices = new HashMap<>(known);
        prices.putAll(pricing.current(storeId, added));

        // Waga sztuki należy do produktu w sklepie, więc z kreatora trafia do kartoteki i służy kolejnym kalkulacjom.
        request.items().forEach(item -> {
            Product ingredient = found.get(item.productId());
            if (ingredient.isCountedInPieces()) {
                ingredient.setPieceWeight(item.pieceWeight(), user);
            }
        });
        List<PriceCalculation.Ingredient> ingredients = request.items().stream()
                .map(item -> new PriceCalculation.Ingredient(found.get(item.productId()), item.quantity()))
                .toList();
        BigDecimal batchYield = request.mode() == CalculationMode.PER_BATCH ? request.batchYield() : null;
        calculation.update(finished, request.mode(), batchYield, request.vatRate(), ingredients, prices, user);
        calculation.setManualPrice(request.manualPrice(), user);
    }

    private static boolean isFinished(Product product) {
        String nomenclature = product.getNomenclature();
        return nomenclature != null && FINISHED_NOMENCLATURES.stream().anyMatch(nomenclature::startsWith);
    }

    private static List<PriceCalculation> sorted(List<PriceCalculation> list) {
        return list.stream().sorted(Comparator.comparing(PriceCalculation::getName, POLISH)).toList();
    }

    private PriceCalculation active(UUID storeId, UUID id) {
        return calculations.findInStore(storeId, id)
                .filter(calculation -> calculation.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kalkulacji"));
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
