package com.intermanage.backend.dataentry.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.CatalogListItem;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.ProductLink;
import com.intermanage.backend.dataentry.dto.ProductLinkRequest;
import com.intermanage.backend.dataentry.entity.DataEntryProductLink;
import com.intermanage.backend.dataentry.repository.DataEntryCatalogRepository;
import com.intermanage.backend.dataentry.repository.DataEntryProductLinkRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Przypinanie pozycji faktury bez EAN-u (albo z EAN-em spoza kartoteki) do artykułu z kartoteki: propozycje podobnych
 * artykułów (pg_trgm i początki słów, bo kartoteka skraca nazwy), zapis przypięcia per sklep, NIP dostawcy i kod
 * z kolumny EAN oraz odpięcie (znacznik). Przy wczytaniu faktury przypięcia stosuje {@link DataEntryService#invoice}.
 */
@Service
public class DataEntryLinkService {

    static final int SUGGESTIONS = 10;
    private static final int MAX_PREFIXES = 6;
    private static final int PREFIX_LENGTH = 3;

    private final DataEntryProductLinkRepository links;
    private final DataEntryCatalogRepository catalog;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final DataEntryService dataEntry;

    public DataEntryLinkService(DataEntryProductLinkRepository links, DataEntryCatalogRepository catalog,
            ProductRepository products, StoreRepository stores, AppUserRepository users, DataEntryService dataEntry) {
        this.links = links;
        this.catalog = catalog;
        this.products = products;
        this.stores = stores;
        this.users = users;
        this.dataEntry = dataEntry;
    }

    /**
     * Do {@link #SUGGESTIONS} artykułów podobnych do tekstu z faktury, najpierw najbardziej podobne; towar dostawcy
     * o tym NIP-ie ma premię. Opis kraju pochodzenia ("KR. POCH. PL") jest pomijany.
     */
    @Transactional(readOnly = true)
    public List<CatalogListItem> suggestions(UUID storeId, String text, String sellerNip) {
        String search = searchText(text);
        if (search.isEmpty()) {
            return List.of();
        }
        String[] prefixes = prefixes(search);
        catalog.useSimilarityThreshold();
        return catalog.findLinkCandidates(storeId, search, prefixes, candidatePattern(prefixes, search),
                        Math.max(1, Math.min(2, prefixes.length)), digits(sellerNip), SUGGESTIONS).stream()
                .map(row -> new CatalogListItem(row.getItm8(), row.getEan(), row.getName(), row.getBrand(),
                        row.getSupplier(), row.getSubfamilyCode(), row.getSubfamilyName(), row.getPurchasePrice(),
                        row.getSalePrice(), row.getStock(), row.getRotation()))
                .toList();
    }

    /**
     * Przypina kod dostawcy do artykułu; poprzednie przypięcie tego kodu do innego artykułu dostaje znacznik. Zwraca
     * artykuł z danymi jak przy wpisaniu EAN-u.
     */
    @Transactional
    public ProductLink link(UUID storeId, ProductLinkRequest request, UUID userId) {
        String nip = digits(request.sellerNip());
        String code = code(request.code());
        if (nip.isEmpty() || code.isEmpty()) {
            throw ApiException.badRequest("Brak NIP-u dostawcy albo kodu pozycji");
        }
        Product product = products.findByStoreIdAndItm8(storeId, request.itm8().strip())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu w kartotece"));
        if (product.getEan() == null || product.getEan().isBlank()) {
            throw ApiException.badRequest("Artykuł nie ma EAN-u, więc nie trafi do tabeli");
        }
        AppUser user = users.getReferenceById(userId);
        DataEntryProductLink link = links.findByStoreIdAndSellerNipAndInvoiceCodeAndDeletedAtIsNull(storeId, nip, code)
                .orElse(null);
        if (link != null && !link.getProduct().getId().equals(product.getId())) {
            link.delete(user);
            links.saveAndFlush(link);
            link = null;
        }
        if (link == null) {
            link = links.save(new DataEntryProductLink(stores.getReferenceById(storeId), nip, code, product, user));
        }
        return new ProductLink(link.getId(), code, catalogProduct(storeId, product));
    }

    /** Odpięcie (znacznik): kolejne wczytanie faktury pokaże pozycję znowu bez artykułu. */
    @Transactional
    public void unlink(UUID storeId, UUID linkId, UUID userId) {
        DataEntryProductLink link = links.findByIdAndStoreId(linkId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przypięcia"));
        if (!link.isDeleted()) {
            link.delete(users.getReferenceById(userId));
        }
    }

    private CatalogProduct catalogProduct(UUID storeId, Product product) {
        return dataEntry.lookup(storeId, List.of(product.getEan())).stream()
                .filter(found -> product.getItm8().equals(found.itm8())).findFirst()
                .orElseThrow(() -> ApiException.conflict("Pod EAN-em artykułu w kartotece jest inny artykuł"));
    }

    /** Kod pozycji jako klucz przypięcia: wielkie litery bez polskich znaków, pojedyncze spacje. */
    public static String code(String raw) {
        return raw == null ? "" : plain(raw).replaceAll("\\s+", " ").strip();
    }

    /** Tekst do szukania: same litery i cyfry, bez opisu kraju pochodzenia ("KR. POCH. PL" i dalej). */
    static String searchText(String raw) {
        String text = raw == null ? "" : plain(raw).replaceAll("[^A-Z0-9]+", " ").strip();
        return text.replaceFirst("\\bKR POCH\\b.*$", "").strip();
    }

    /** Początki słów z liter (od 3 liter) do dopasowania skrótów z kartoteki: "SZYNKA KRUCHA" -> SZY, KRU. */
    static String[] prefixes(String search) {
        List<String> result = new ArrayList<>();
        for (String word : search.split(" ")) {
            if (word.length() >= PREFIX_LENGTH && word.chars().allMatch(Character::isLetter)) {
                String prefix = word.substring(0, PREFIX_LENGTH);
                if (!result.contains(prefix)) {
                    result.add(prefix);
                }
            }
        }
        return result.stream().limit(MAX_PREFIXES).toArray(String[]::new);
    }

    /**
     * Wzorzec do zawężenia kandydatów indeksem trigramowym: któryś z początków słów, litery z polskimi odpowiednikami
     * ({@code CZE} -> {@code [CĆ][ZŹŻ][EĘ]}), bo nazwy w kartotece bywają z polskimi znakami. Bez początków słów
     * (np. same cyfry) cała nazwa równa tekstowi; {@code search} ma już tylko litery, cyfry i spacje.
     */
    static String candidatePattern(String[] prefixes, String search) {
        if (prefixes.length == 0) {
            return "^" + search + "$";
        }
        List<String> parts = new ArrayList<>();
        for (String prefix : prefixes) {
            StringBuilder part = new StringBuilder();
            for (char c : prefix.toCharArray()) {
                String variants = POLISH_VARIANTS.get(c);
                part.append(variants != null ? "[" + c + variants + "]" : String.valueOf(c));
            }
            parts.add(part.toString());
        }
        return "(" + String.join("|", parts) + ")";
    }

    private static final Map<Character, String> POLISH_VARIANTS = Map.of('A', "Ą", 'C', "Ć",
            'E', "Ę", 'L', "Ł", 'N', "Ń", 'O', "Ó", 'S', "Ś", 'Z', "ŹŻ");

    private static String plain(String raw) {
        return Normalizer.normalize(raw.toUpperCase(Locale.ROOT).replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    public static String digits(String nip) {
        return nip == null ? "" : nip.replaceAll("\\D", "");
    }
}
