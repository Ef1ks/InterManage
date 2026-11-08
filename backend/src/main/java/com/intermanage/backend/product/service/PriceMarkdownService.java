package com.intermanage.backend.product.service;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.LabelFile;
import com.intermanage.backend.dataentry.service.DataEntryProtocolService.ProtocolFile;
import com.intermanage.backend.product.dto.PriceMarkdownItem;
import com.intermanage.backend.product.dto.PriceMarkdownRequest;
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

    /** Ten sam format cenówki co przy ustawianiu promocji. */
    static final String LABEL_FORMAT = "PRO_PRO_FOR_67";

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
        if (request.validTo().isBefore(request.validFrom())) {
            throw ApiException.badRequest("Koniec przeceny nie może być przed początkiem");
        }
        Product product = products.findByStoreIdAndItm8(storeId, request.itm8())
                .orElseThrow(() -> ApiException.notFound("Artykułu nie ma w kartotece sklepu"));
        if (product.getEan() == null || product.getEan().isBlank()) {
            throw ApiException.badRequest("Artykuł nie ma EAN-u, więc nie można go przecenić");
        }
        markdowns.saveAndFlush(new PriceMarkdown(storeId, product, request.newPrice(), request.validFrom(),
                request.validTo(), user(userId)));
        return list(storeId);
    }

    /** Plik ETIQUETTE z wybranych przecen: po jednej cenówce na artykuł, bez znacznika (stawia go zapis na dysku). */
    @Transactional(readOnly = true)
    public ProtocolFile labels(UUID storeId, List<UUID> ids, String username) {
        List<LabelFile.Line> lines = markdowns.findByStoreIdAndIdIn(storeId, ids).stream()
                .filter(markdown -> markdown.getDeletedAt() == null).map(PriceMarkdown::getEan).distinct()
                .map(ean -> new LabelFile.Line(ean, 1)).toList();
        byte[] content = LabelFile.content(lines);
        if (content.length == 0) {
            throw ApiException.badRequest("Brak artykułów z EAN-em do cenówek");
        }
        return new ProtocolFile(LabelFile.fileName(username, LABEL_FORMAT), content);
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
