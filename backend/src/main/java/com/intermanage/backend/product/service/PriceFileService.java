package com.intermanage.backend.product.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.component.PriceFileParser;
import com.intermanage.backend.product.dto.ParsedPriceFile;
import com.intermanage.backend.product.dto.PendingPriceFilesResponse;
import com.intermanage.backend.product.dto.PriceFileImportResponse;
import com.intermanage.backend.product.dto.PriceFileLine;
import com.intermanage.backend.product.dto.PriceFileOverview;
import com.intermanage.backend.product.dto.PriceFileUploadResponse;
import com.intermanage.backend.product.entity.PriceFileImport;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.mapper.PriceFileMapper;
import com.intermanage.backend.product.repository.PriceFileImportRepository;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Ceny z plików TRACEART, które system sklepu zapisuje w ciągu dnia przy zmianach cen na kasie. Przeglądarka pyta,
 * których plików z folderu serwer jeszcze nie ma ({@link #pending}), i wysyła je; każdy plik jest wczytywany raz
 * (nazwa unikalna w sklepie), od najstarszego. Cena artykułu to cena jego głównego EAN-u z kartoteki, a gdy go w pliku
 * nie ma, najwyższa cena spośród kodów artykułu (kody z etykiet obniżek mają niższe ceny). Plik starszy niż raport
 * kartoteki albo poprzedni plik artykułu ceny nie zmienia ({@link Product#applyPriceFile}).
 */
@Service
public class PriceFileService {

    private static final Logger log = LoggerFactory.getLogger(PriceFileService.class);

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final int HISTORY_SIZE = 20;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_FILES = 200;

    private final PriceFileParser parser;
    private final PriceFileMapper mapper;
    private final PriceFileImportRepository imports;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public PriceFileService(PriceFileParser parser, PriceFileMapper mapper, PriceFileImportRepository imports,
            ProductRepository products, StoreRepository stores, AppUserRepository users) {
        this.parser = parser;
        this.mapper = mapper;
        this.imports = imports;
        this.products = products;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public PriceFileOverview overview(UUID storeId) {
        List<PriceFileImportResponse> latest = imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream()
                .map(mapper::toResponse)
                .toList();
        return new PriceFileOverview(latest, imports.countActivePromotions(storeId, LocalDate.now(STORE_ZONE)));
    }

    /** Pliki TRACEART z listy, których sklep jeszcze nie wczytał, od najstarszego; inne nazwy są pomijane. */
    @Transactional(readOnly = true)
    public PendingPriceFilesResponse pending(UUID storeId, List<String> fileNames) {
        List<String> candidates = fileNames.stream()
                .filter(name -> name != null && PriceFileParser.fileTime(name) != null)
                .distinct()
                .toList();
        Set<String> imported = candidates.isEmpty() ? Set.of()
                : new HashSet<>(imports.findImportedNames(storeId, candidates));
        return new PendingPriceFilesResponse(candidates.stream()
                .filter(name -> !imported.contains(name))
                .sorted(byFileTime())
                .toList());
    }

    @Transactional
    public PriceFileUploadResponse upload(UUID storeId, List<MultipartFile> files, UUID userId) {
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("Wybierz pliki TRACEART do wczytania");
        }
        if (files.size() > MAX_FILES) {
            throw ApiException.badRequest("Naraz można wysłać najwyżej " + MAX_FILES + " plików");
        }
        Map<String, ParsedPriceFile> parsed = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() != null
                    ? Path.of(file.getOriginalFilename()).getFileName().toString() : "";
            if (PriceFileParser.fileTime(name) == null) {
                throw ApiException.badRequest("To nie jest plik TRACEART: " + name);
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw ApiException.badRequest("Plik " + name + " jest za duży");
            }
            try {
                parsed.putIfAbsent(name, parser.parse(name, file.getBytes()));
            } catch (IOException e) {
                throw ApiException.badRequest("Nie udało się odczytać pliku " + name);
            }
        }

        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        // Ta sama blokada co import kartoteki: oba zmieniają ceny, a kilka kart przeglądarki może wysłać ten sam plik.
        products.lockForImport(ProductImportService.IMPORT_LOCK_PREFIX + storeId);
        Set<String> already = new HashSet<>(imports.findImportedNames(storeId, parsed.keySet()));
        AppUser user = userId != null ? users.getReferenceById(userId) : null;

        List<PriceFileImportResponse> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String name : parsed.keySet().stream().sorted(byFileTime()).toList()) {
            if (already.contains(name)) {
                skipped.add(name);
                continue;
            }
            PriceFileImport result = apply(store, name, parsed.get(name), user);
            imported.add(mapper.toResponse(result));
            log.info("Plik cen {} w sklepie {}: artykuły {}, zmienione {}, starsze {}, spoza kartoteki {}", name,
                    store.getNumber(), result.getArticleCount(), result.getUpdatedCount(), result.getOlderCount(),
                    result.getUnknownCount());
        }
        return new PriceFileUploadResponse(imported, skipped);
    }

    private PriceFileImport apply(Store store, String name, ParsedPriceFile file, AppUser user) {
        PriceFileImport priceFile = imports.save(new PriceFileImport(store, name, file.fileTime(), user,
                file.totalLines()));
        Map<String, List<PriceFileLine>> byItm8 = file.lines().stream()
                .collect(Collectors.groupingBy(PriceFileLine::itm8, LinkedHashMap::new, Collectors.toList()));
        Map<String, Product> catalog = products.findByStoreIdAndItm8In(store.getId(), byItm8.keySet()).stream()
                .collect(Collectors.toMap(Product::getItm8, product -> product));
        int updated = 0;
        int older = 0;
        int unknown = 0;
        for (Map.Entry<String, List<PriceFileLine>> entry : byItm8.entrySet()) {
            Product product = catalog.get(entry.getKey());
            if (product == null) {
                unknown++;
                continue;
            }
            switch (product.applyPriceFile(articleLine(product, entry.getValue()), priceFile)) {
                case UPDATED -> updated++;
                case OLDER -> older++;
                case UNCHANGED -> {
                }
            }
        }
        priceFile.recordResult(byItm8.size(), updated, older, unknown);
        return priceFile;
    }

    /** Linia głównego EAN-u artykułu, a bez niej kod z najwyższą ceną na kasie. */
    private static PriceFileLine articleLine(Product product, List<PriceFileLine> lines) {
        String mainEan = withoutLeadingZeros(product.getEan());
        return lines.stream()
                .filter(line -> withoutLeadingZeros(line.ean()).equals(mainEan))
                .findFirst()
                .orElseGet(() -> lines.stream().max(Comparator.comparing(PriceFileService::tillPrice)).orElseThrow());
    }

    private static BigDecimal tillPrice(PriceFileLine line) {
        return line.promoPrice() != null ? line.promoPrice() : line.salePrice();
    }

    private static String withoutLeadingZeros(String ean) {
        return ean == null ? "" : ean.replaceFirst("^0+", "");
    }

    private static Comparator<String> byFileTime() {
        return Comparator.comparing((String name) -> {
            LocalDateTime time = PriceFileParser.fileTime(name);
            return time != null ? time : LocalDateTime.MIN;
        }).thenComparing(Comparator.naturalOrder());
    }
}
