package com.intermanage.backend.document.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.GratisArticleItem;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentGratisArticle;
import com.intermanage.backend.document.mapper.DocumentGratisArticleMapper;
import com.intermanage.backend.document.repository.DocumentGratisArticleRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;

/**
 * Lista artykułów sklepu, które w przeniesieniach stanów oznaczają gratis (dawniej rozpoznawane po słowie GRATIS w
 * nazwie). Taki artykuł na minusie robi z przeniesienia kandydata na gratis, a na plusie to błąd do sprawdzenia.
 */
@Service
public class DocumentGratisArticleService {

    /** Kandydat na gratis ({@code onMinus}) i artykuł z listy na plusie, czyli coś nie tak ({@code onPlus}). */
    public record Marks(boolean onMinus, boolean onPlus) {
        public static final Marks NONE = new Marks(false, false);
    }

    private static final int ITM8_LENGTH = 13;

    private final DocumentGratisArticleRepository articles;
    private final ProductRepository products;
    private final AppUserRepository users;
    private final DocumentGratisArticleMapper mapper;

    public DocumentGratisArticleService(DocumentGratisArticleRepository articles, ProductRepository products,
            AppUserRepository users, DocumentGratisArticleMapper mapper) {
        this.articles = articles;
        this.products = products;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<GratisArticleItem> list(UUID storeId) {
        return articles.findActive(storeId).stream().map(mapper::toItem).toList();
    }

    /** Artykuł już na liście zostaje z pierwszym wpisem, więc drugie dodanie niczego nie psuje. */
    @Transactional
    public GratisArticleItem add(UUID storeId, String itm8, UUID userId) {
        Product product = products.findByStoreIdAndItm8(storeId, padded(itm8))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu w kartotece sklepu"));
        return mapper.toItem(articles.findActiveByProduct(product.getId()).orElseGet(() -> articles.save(
                new DocumentGratisArticle(product, users.findById(userId).orElseThrow()))));
    }

    @Transactional
    public void remove(UUID storeId, UUID id, UUID userId) {
        articles.findActiveInStore(storeId, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu na liście gratisów"))
                .remove(users.getReferenceById(userId));
    }

    @Transactional(readOnly = true)
    public Set<String> itm8s(UUID storeId) {
        return articles.findActiveItm8s(storeId);
    }

    /** Oznaczenia dokumentów, które mogą być gratisem (przeniesienia stanów); inne dokumenty ich nie mają. */
    @Transactional(readOnly = true)
    public Map<UUID, Marks> marks(UUID storeId, Collection<Document> documents) {
        List<UUID> ids = documents.stream().filter(document -> document.getType().canBeGratis())
                .map(Document::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return articles.findMarks(storeId, ids).stream().collect(Collectors.toMap(
                DocumentGratisArticleRepository.Marks::getDocumentId,
                row -> new Marks(row.getOnMinus(), row.getOnPlus()), (first, second) -> first));
    }

    public Marks marksOf(UUID storeId, Document document) {
        return marks(storeId, List.of(document)).getOrDefault(document.getId(), Marks.NONE);
    }

    private static String padded(String itm8) {
        String trimmed = itm8.trim();
        return trimmed.matches("\\d{1,12}") ? "0".repeat(ITM8_LENGTH - trimmed.length()) + trimmed : trimmed;
    }
}
