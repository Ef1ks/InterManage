package com.intermanage.backend.leaflet.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.basedelivery.repository.MeaConfirmationRepository;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.leaflet.component.LeafletParser;
import com.intermanage.backend.leaflet.dto.LeafletLineResponse;
import com.intermanage.backend.leaflet.dto.LeafletLinkRequest;
import com.intermanage.backend.leaflet.dto.LeafletMatch;
import com.intermanage.backend.leaflet.dto.LeafletResponse;
import com.intermanage.backend.leaflet.dto.LeafletRow;
import com.intermanage.backend.leaflet.dto.LeafletSummary;
import com.intermanage.backend.leaflet.entity.PromoLeaflet;
import com.intermanage.backend.leaflet.entity.PromoLeafletLine;
import com.intermanage.backend.leaflet.entity.PromoLeafletLink;
import com.intermanage.backend.leaflet.mapper.LeafletMapper;
import com.intermanage.backend.leaflet.repository.PromoLeafletLinkRepository;
import com.intermanage.backend.leaflet.repository.PromoLeafletRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Opracowanie gazetki: wgranie pliku z centrali i artykuł z kartoteki dla każdego EAN-u z gazetki. Artykuł wybrany
 * ręcznie (przypięcie) ma pierwszeństwo i zostaje w sklepie na kolejne gazetki; bez niego szukamy po EAN-ie tak jak
 * we wprowadzaniu danych ({@link DataEntryService#lookup}: bieżący EAN, multikody, dawne pary, kod z etykiety wagi).
 */
@Service
public class LeafletService {

    private static final Logger log = LoggerFactory.getLogger(LeafletService.class);

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** Tyle kodów {@link DataEntryService#lookup} przyjmuje w jednym wywołaniu. */
    private static final int LOOKUP_BATCH = 500;
    /** Zip (.xlsx, .xlsm). */
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};
    /** Oznaczenie pozycji bez kodu MEA w gazetce (do opracowania w sklepie, nie jest kodem do wpisania). */
    private static final String NO_MEA = "DDD";

    private final PromoLeafletRepository leaflets;
    private final PromoLeafletLinkRepository links;
    private final MeaConfirmationRepository meaConfirmations;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final ProductRepository products;
    private final DataEntryService dataEntry;
    private final LeafletParser parser;
    private final XlsxReader xlsxReader;
    private final LeafletMapper mapper;

    public LeafletService(PromoLeafletRepository leaflets, PromoLeafletLinkRepository links,
            MeaConfirmationRepository meaConfirmations, StoreRepository stores, AppUserRepository users,
            ProductRepository products, DataEntryService dataEntry, LeafletParser parser, XlsxReader xlsxReader,
            LeafletMapper mapper) {
        this.leaflets = leaflets;
        this.links = links;
        this.meaConfirmations = meaConfirmations;
        this.stores = stores;
        this.users = users;
        this.products = products;
        this.dataEntry = dataEntry;
        this.parser = parser;
        this.xlsxReader = xlsxReader;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<LeafletSummary> list(UUID storeId) {
        return leaflets.findTop60ByStoreIdAndReplacedAtIsNullOrderByYearDescWeekDesc(storeId).stream()
                .map(mapper::toSummary).toList();
    }

    /** Wgranie gazetki; ta sama gazetka (rok i tydzień) wgrana ponownie dostaje nowy wpis, a poprzednia znacznik. */
    @Transactional
    public LeafletResponse upload(UUID storeId, MultipartFile file, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String fileName = file.getOriginalFilename() == null ? "gazetka.xlsx" : file.getOriginalFilename();
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do wgrania");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej 20 MB)");
        }
        LeafletParser.Parsed parsed;
        try {
            parsed = parser.parse(sheet(file, fileName));
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        }
        AppUser by = users.getReferenceById(userId);
        leaflets.findByStoreIdAndYearAndWeekAndReplacedAtIsNull(storeId, (short) parsed.year(), (short) parsed.week())
                .ifPresent(previous -> {
                    previous.replace(by);
                    // Znacznik przed nowym wpisem: aktywna może być tylko jedna gazetka o tym numerze (indeks unikalny).
                    leaflets.flush();
                });
        PromoLeaflet leaflet = new PromoLeaflet(store, parsed.year(), parsed.week(), fileName, by);
        for (LeafletRow row : parsed.rows()) {
            leaflet.addLine(new PromoLeafletLine(leaflet, row));
        }
        leaflets.saveAndFlush(leaflet);
        return response(storeId, leaflet);
    }

    @Transactional(readOnly = true)
    public LeafletResponse get(UUID storeId, UUID id) {
        PromoLeaflet leaflet = leaflets.findByIdAndStoreIdAndReplacedAtIsNull(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono gazetki"));
        return response(storeId, leaflet);
    }

    /** Przypina artykuł do EAN-u z gazetki; poprzednie przypięcie dostaje znacznik, a nowe pamięta je do cofnięcia. */
    @Transactional
    public LeafletMatch link(UUID storeId, LeafletLinkRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        Product product = products.findByStoreIdAndItm8(storeId, request.itm8())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu w kartotece"));
        if (product.getEan() == null || product.getEan().isBlank()) {
            throw ApiException.badRequest("Artykuł nie ma EAN-u w kartotece");
        }
        String ean = key(request.ean());
        if (ean.isEmpty()) {
            throw ApiException.badRequest("EAN z gazetki to same zera");
        }
        AppUser by = users.getReferenceById(userId);
        PromoLeafletLink previous = links.findByStoreIdAndEanAndDeletedAtIsNull(storeId, ean).orElse(null);
        if (previous != null) {
            previous.delete(by);
            links.flush();
        }
        links.saveAndFlush(new PromoLeafletLink(store, ean, product, previous, by));
        return match(storeId, ean);
    }

    /**
     * Usuwa przypięcie (znacznik). Gdy zastąpiło inne, a EAN nie ma teraz innego przypięcia, poprzednie wraca, więc
     * „Cofnij” po podmianie wraca do stanu sprzed niej.
     */
    @Transactional
    public LeafletMatch unlink(UUID storeId, UUID linkId, UUID userId) {
        PromoLeafletLink link = links.findByIdAndStoreId(linkId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przypięcia artykułu"));
        if (link.getDeletedAt() == null) {
            link.delete(users.getReferenceById(userId));
            links.flush();
            PromoLeafletLink previous = link.getPrevious();
            if (previous != null && previous.getDeletedAt() != null
                    && links.findByStoreIdAndEanAndDeletedAtIsNull(storeId, link.getEan()).isEmpty()) {
                previous.restore();
                links.flush();
            }
        }
        return match(storeId, link.getEan());
    }

    /** Przywraca usunięte przypięcie, gdy EAN nie ma w tej chwili innego. */
    @Transactional
    public LeafletMatch restore(UUID storeId, UUID linkId, UUID userId) {
        PromoLeafletLink link = links.findByIdAndStoreId(linkId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przypięcia artykułu"));
        if (link.getDeletedAt() != null) {
            links.findByStoreIdAndEanAndDeletedAtIsNull(storeId, link.getEan()).ifPresent(current -> {
                current.delete(users.getReferenceById(userId));
                links.flush();
            });
            link.restore();
            links.flush();
        }
        return match(storeId, link.getEan());
    }

    private LeafletMatch match(UUID storeId, String ean) {
        Resolved resolved = resolve(storeId, List.of(ean));
        CatalogProduct product = resolved.products().get(ean);
        return new LeafletMatch(ean, resolved.linkIds().get(ean), product, resolved.regularPrice(product));
    }

    private LeafletResponse response(UUID storeId, PromoLeaflet leaflet) {
        List<PromoLeafletLine> lines = leaflet.getLines();
        Resolved resolved = resolve(storeId, lines.stream().map(line -> key(line.getEan())).toList());
        List<LeafletLineResponse> responses = new ArrayList<>(lines.size());
        for (PromoLeafletLine line : lines) {
            String key = key(line.getEan());
            CatalogProduct product = resolved.products().get(key);
            responses.add(mapper.toLine(line, product, resolved.regularPrice(product), resolved.linkIds().get(key)));
        }
        Set<String> confirmed = meaConfirmations.findActiveCodes(storeId);
        LocalDate today = LocalDate.now(STORE_ZONE);
        List<String> pendingMea = lines.stream().filter(line -> !line.getDateTo().isBefore(today))
                .map(PromoLeafletLine::getMeaCode)
                .filter(code -> code != null && !NO_MEA.equals(code) && !confirmed.contains(code))
                .collect(Collectors.toCollection(LinkedHashSet::new)).stream().sorted().toList();
        return mapper.toResponse(leaflet, responses, pendingMea);
    }

    /**
     * Artykuły dla EAN-ów z gazetki (klucze bez zer wiodących): najpierw przypięcia sklepu (artykuł szukany po jego
     * bieżącym EAN-ie, żeby miał te same dane co przy wpisaniu EAN-u), potem po EAN-ie z gazetki.
     */
    private Resolved resolve(UUID storeId, Collection<String> keys) {
        List<String> distinct = keys.stream().filter(key -> !key.isEmpty()).distinct().toList();
        Map<String, PromoLeafletLink> linked = links.findByStoreIdAndEanInAndDeletedAtIsNull(storeId, distinct)
                .stream().collect(Collectors.toMap(PromoLeafletLink::getEan, Function.identity()));
        Map<String, String> searched = new HashMap<>();
        for (String key : distinct) {
            PromoLeafletLink link = linked.get(key);
            searched.put(key, link != null ? key(link.getProduct().getEan()) : key);
        }
        Map<String, CatalogProduct> found = lookup(storeId, searched.values());
        Map<String, CatalogProduct> byKey = new HashMap<>();
        Map<String, UUID> linkIds = new HashMap<>();
        for (String key : distinct) {
            CatalogProduct product = found.get(searched.get(key));
            if (product != null) {
                byKey.put(key, product);
            }
            PromoLeafletLink link = linked.get(key);
            if (link != null) {
                linkIds.put(key, link.getId());
            }
        }
        Map<String, BigDecimal> regular = new HashMap<>();
        products.findByStoreIdAndItm8In(storeId, byKey.values().stream().map(CatalogProduct::itm8)
                .filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(product -> regular.put(product.getItm8(), product.getSalePrice()));
        return new Resolved(byKey, linkIds, regular);
    }

    private Map<String, CatalogProduct> lookup(UUID storeId, Collection<String> keys) {
        // lookup bierze najwyżej LOOKUP_BATCH kodów naraz, a gazetka ma ich ponad tysiąc.
        List<String> eans = keys.stream().filter(key -> !key.isEmpty()).distinct().toList();
        Map<String, CatalogProduct> catalog = new HashMap<>();
        for (int from = 0; from < eans.size(); from += LOOKUP_BATCH) {
            dataEntry.lookup(storeId, eans.subList(from, Math.min(from + LOOKUP_BATCH, eans.size())))
                    .forEach(product -> catalog.putIfAbsent(product.ean(), product));
        }
        return catalog;
    }

    /** Artykuły dla kluczy EAN-ów, przypięcia i ceny sprzedaży z kartoteki po ITM8. */
    private record Resolved(Map<String, CatalogProduct> products, Map<String, UUID> linkIds,
            Map<String, BigDecimal> regularByItm8) {

        /**
         * Cena sprzedaży z kartoteki bez promocji z kasy ({@code products.sale_price} to w promocji cena regularna);
         * szkic artykułu z pliku CADBEZP (bez ITM8) ma cenę ze szkicu.
         */
        BigDecimal regularPrice(CatalogProduct product) {
            if (product == null) {
                return null;
            }
            BigDecimal regular = product.itm8() == null ? null : regularByItm8.get(product.itm8());
            return regular != null ? regular : product.promoPrice() == null ? product.salePrice() : null;
        }
    }

    /** Klucz EAN-u: same cyfry bez zer wiodących (GTIN-14 = EAN-13). */
    static String key(String ean) {
        return ean == null ? "" : ean.replaceAll("\\D", "").replaceFirst("^0+", "");
    }

    private List<List<String>> sheet(MultipartFile file, String fileName) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length < ZIP_SIGNATURE.length
                    || !Arrays.equals(bytes, 0, ZIP_SIGNATURE.length, ZIP_SIGNATURE, 0, ZIP_SIGNATURE.length)) {
                throw ApiException.badRequest("Plik " + fileName + " to nie skoroszyt .xlsx. Otwórz go w Excelu "
                        + "i zapisz jako skoroszyt .xlsx.");
            }
            Path temp = Files.createTempFile("leaflet-", ".xlsx");
            try {
                Files.write(temp, bytes);
                List<List<String>> rows = new ArrayList<>();
                xlsxReader.readFirstSheet(temp, (rowNumber, cells) -> {
                    while (rows.size() < rowNumber - 1) {
                        rows.add(List.of());
                    }
                    rows.add(cells);
                });
                return rows;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (InvalidWorkbookException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku {}", fileName, e);
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        }
    }
}
