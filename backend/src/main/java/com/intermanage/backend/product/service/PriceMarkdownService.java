package com.intermanage.backend.product.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.LabelFile;
import com.intermanage.backend.dataentry.service.DataEntryProtocolService.ProtocolFile;
import com.intermanage.backend.product.dto.PriceMarkdownItem;
import com.intermanage.backend.product.dto.PriceMarkdownRequest;
import com.intermanage.backend.product.entity.PriceLabelKind;
import com.intermanage.backend.product.entity.PriceMarkdown;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.mapper.PriceMarkdownMapper;
import com.intermanage.backend.product.repository.PriceMarkdownRepository;
import com.intermanage.backend.product.repository.ProductRepository;

/**
 * Lista cenówek po przecenach z karty artykułu. Cenę w programie sklepu zmienia makro (składa je przeglądarka), a tu
 * zostaje wpis, z którego powstaje plik ETIQUETTE. Znacznik "w pliku" stawia dopiero zapis pliku na dysku, więc nieudany
 * zapis niczego nie gubi; usunięcie z listy to znacznik.
 */
@Service
public class PriceMarkdownService {

    private final PriceMarkdownRepository markdowns;
    private final ProductRepository products;
    private final PriceMarkdownMapper mapper;
    private final AppUserRepository users;

    public PriceMarkdownService(PriceMarkdownRepository markdowns, ProductRepository products,
            PriceMarkdownMapper mapper, AppUserRepository users) {
        this.markdowns = markdowns;
        this.products = products;
        this.mapper = mapper;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<PriceMarkdownItem> list(UUID storeId) {
        return markdowns.findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(storeId).stream().map(mapper::toItem)
                .toList();
    }

    @Transactional
    public List<PriceMarkdownItem> create(UUID storeId, PriceMarkdownRequest request, UUID userId) {
        markdowns.saveAndFlush(markdown(storeId, request, user(userId)));
        return list(storeId);
    }

    /** Wszystkie albo żadna: błąd jednego artykułu wycofuje całość. */
    @Transactional
    public List<PriceMarkdownItem> createAll(UUID storeId, List<PriceMarkdownRequest> requests, UUID userId) {
        AppUser user = user(userId);
        markdowns.saveAllAndFlush(requests.stream().map(request -> markdown(storeId, request, user)).toList());
        return list(storeId);
    }

    private PriceMarkdown markdown(UUID storeId, PriceMarkdownRequest request, AppUser user) {
        if (request.validTo().isBefore(request.validFrom())) {
            throw ApiException.badRequest("Koniec przeceny nie może być przed początkiem");
        }
        Product product = products.findByStoreIdAndItm8(storeId, request.itm8())
                .orElseThrow(() -> ApiException.notFound("Artykułu nie ma w kartotece sklepu: " + request.itm8()));
        if (product.getEan() == null || product.getEan().isBlank()) {
            throw ApiException.badRequest("Artykuł nie ma EAN-u, więc nie można go przecenić");
        }
        return new PriceMarkdown(storeId, product, request.newPrice(), request.validFrom(), request.validTo(), user);
    }

    /**
     * Zmiany cen z pliku CTRLPRIX (artykuł z kartoteki → nowa cena) na liście cenówek, od dziś. Zmiana tego samego
     * artykułu, która jeszcze czeka na cenówkę, ustępuje nowszej (znacznik), żeby w pliku nie było starej ceny.
     */
    @Transactional
    public void addPriceChanges(UUID storeId, Map<Product, BigDecimal> prices, UUID priceControlFileId, UUID userId) {
        if (prices.isEmpty()) {
            return;
        }
        AppUser user = user(userId);
        Set<String> itm8s = prices.keySet().stream().map(Product::getItm8).collect(Collectors.toSet());
        markdowns.findPendingPriceChanges(storeId, itm8s).forEach(previous -> previous.delete(user));
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        markdowns.saveAllAndFlush(prices.entrySet().stream()
                .filter(entry -> entry.getKey().getEan() != null && !entry.getKey().getEan().isBlank())
                .map(entry -> PriceMarkdown.priceChange(storeId, entry.getKey(), entry.getValue(), today,
                        priceControlFileId, user))
                .toList());
    }

    /**
     * Plik ETIQUETTE z wybranych wpisów: po jednej cenówce na artykuł, w formacie ich rodzaju (przecena i zmiana ceny
     * mają różne cenówki, więc jeden plik to jeden rodzaj), bez znacznika (stawia go zapis na dysku).
     */
    @Transactional(readOnly = true)
    public ProtocolFile labels(UUID storeId, List<UUID> ids, String username) {
        List<PriceMarkdown> chosen = markdowns.findByStoreIdAndIdIn(storeId, ids).stream()
                .filter(markdown -> markdown.getDeletedAt() == null).toList();
        Set<PriceLabelKind> kinds = chosen.stream().map(PriceMarkdown::getKind).collect(Collectors.toSet());
        if (kinds.size() > 1) {
            throw ApiException.badRequest("Przeceny i zmiany cen mają różne cenówki: utwórz osobny plik dla każdych");
        }
        List<LabelFile.Line> lines = chosen.stream().map(PriceMarkdown::getEan).distinct()
                .map(ean -> new LabelFile.Line(ean, 1)).toList();
        byte[] content = LabelFile.content(lines);
        if (content.length == 0) {
            throw ApiException.badRequest("Brak artykułów z EAN-em do cenówek");
        }
        return new ProtocolFile(LabelFile.fileName(username, kinds.iterator().next().labelFormat()), content);
    }

    @Transactional
    public List<PriceMarkdownItem> markLabelsGenerated(UUID storeId, List<UUID> ids, UUID userId) {
        return change(storeId, ids, markdown -> markdown.markLabelsGenerated(user(userId)));
    }

    @Transactional
    public List<PriceMarkdownItem> delete(UUID storeId, List<UUID> ids, UUID userId) {
        return change(storeId, ids, markdown -> markdown.delete(user(userId)));
    }

    @Transactional
    public List<PriceMarkdownItem> restore(UUID storeId, List<UUID> ids) {
        return change(storeId, ids, PriceMarkdown::restore);
    }

    private List<PriceMarkdownItem> change(UUID storeId, List<UUID> ids, Consumer<PriceMarkdown> action) {
        markdowns.findByStoreIdAndIdIn(storeId, ids).forEach(action);
        markdowns.flush();
        return list(storeId);
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
