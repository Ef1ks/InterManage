package com.intermanage.backend.deliproduction.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.deliproduction.component.ProductionBaseParser;
import com.intermanage.backend.deliproduction.component.ProductionPdf;
import com.intermanage.backend.deliproduction.dto.ArticleSchemaRequest;
import com.intermanage.backend.deliproduction.dto.ProductionArticleRequest;
import com.intermanage.backend.deliproduction.dto.ProductionBaseResponse;
import com.intermanage.backend.deliproduction.dto.ProductionImportResponse;
import com.intermanage.backend.deliproduction.dto.ProductionSchemaRequest;
import com.intermanage.backend.deliproduction.dto.ProductionSchemaResponse;
import com.intermanage.backend.deliproduction.entity.ProcessParameters;
import com.intermanage.backend.deliproduction.entity.ProductionArticle;
import com.intermanage.backend.deliproduction.entity.ProductionCardSettings;
import com.intermanage.backend.deliproduction.entity.ProductionSchema;
import com.intermanage.backend.deliproduction.mapper.ProductionMapper;
import com.intermanage.backend.deliproduction.repository.ProductionArticleRepository;
import com.intermanage.backend.deliproduction.repository.ProductionCardSettingsRepository;
import com.intermanage.backend.deliproduction.repository.ProductionSchemaRepository;
import com.intermanage.backend.gus.dto.GusCompany;
import com.intermanage.backend.gus.service.GusService;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Baza kart produkcji sklepu: schematy parametrów procesu, artykuły (każdy wybiera schemat), pieczątka zakładu,
 * wgranie bazy z dawnego arkusza i formularz do wydruku. Usuwanie to znacznik.
 */
@Service
public class ProductionBaseService {

    private static final Logger log = LoggerFactory.getLogger(ProductionBaseService.class);

    private final ProductionSchemaRepository schemas;
    private final ProductionArticleRepository articles;
    private final ProductionCardSettingsRepository settings;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final ProductionBaseParser parser;
    private final ProductionPdf pdf;
    private final ProductionMapper mapper;
    private final GusService gus;

    public ProductionBaseService(ProductionSchemaRepository schemas, ProductionArticleRepository articles,
            ProductionCardSettingsRepository settings, StoreRepository stores, AppUserRepository users,
            ProductionBaseParser parser, ProductionPdf pdf, ProductionMapper mapper, GusService gus) {
        this.schemas = schemas;
        this.articles = articles;
        this.settings = settings;
        this.stores = stores;
        this.users = users;
        this.parser = parser;
        this.pdf = pdf;
        this.mapper = mapper;
        this.gus = gus;
    }

    @Transactional(readOnly = true)
    public ProductionBaseResponse base(UUID storeId) {
        List<ProductionArticle> active = articles.findActive(storeId);
        Map<UUID, Long> counts = active.stream()
                .collect(Collectors.groupingBy(article -> article.getSchema().getId(), Collectors.counting()));
        List<ProductionSchemaResponse> schemaList = schemas.findActive(storeId).stream()
                .map(schema -> mapper.toResponse(schema, counts.getOrDefault(schema.getId(), 0L)))
                .toList();
        String custom = settings.findById(storeId).map(ProductionCardSettings::getStamp).orElse(null);
        return new ProductionBaseResponse(schemaList, mapper.toArticles(active),
                custom != null ? custom : defaultStamp(store(storeId)), custom != null);
    }

    /** Linie pieczątki na karcie: zapisana w sklepie albo nazwa firmy z GUS, adres i NIP. */
    @Transactional(readOnly = true)
    public List<String> stamp(UUID storeId) {
        String stamp = settings.findById(storeId).map(ProductionCardSettings::getStamp)
                .orElseGet(() -> defaultStamp(store(storeId)));
        return stamp.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    @Transactional
    public ProductionBaseResponse saveStamp(UUID storeId, String stamp, UUID userId) {
        String value = stamp == null ? "" : stamp.strip();
        if (value.isEmpty()) {
            settings.findById(storeId).ifPresent(settings::delete);
        } else {
            ProductionCardSettings entity = settings.findById(storeId)
                    .orElseGet(() -> new ProductionCardSettings(storeId));
            entity.update(value, users.getReferenceById(userId));
            settings.save(entity);
        }
        return base(storeId);
    }

    @Transactional
    public ProductionBaseResponse createSchema(UUID storeId, ProductionSchemaRequest request, UUID userId) {
        String name = request.name().strip();
        if (schemas.nameTaken(storeId, name, null)) {
            throw ApiException.conflict("Schemat o tej nazwie już jest");
        }
        schemas.save(new ProductionSchema(storeId, name, parameters(request), users.getReferenceById(userId)));
        return base(storeId);
    }

    @Transactional
    public ProductionBaseResponse updateSchema(UUID storeId, UUID schemaId, ProductionSchemaRequest request,
            UUID userId) {
        ProductionSchema schema = activeSchema(storeId, schemaId);
        String name = request.name().strip();
        if (schemas.nameTaken(storeId, name, schemaId)) {
            throw ApiException.conflict("Schemat o tej nazwie już jest");
        }
        schema.update(name, parameters(request), users.getReferenceById(userId));
        return base(storeId);
    }

    /** Usunięcie schematu (znacznik); schemat z artykułami daje 409, bo artykuł zawsze ma schemat. */
    @Transactional
    public void deleteSchema(UUID storeId, UUID schemaId, UUID userId) {
        ProductionSchema schema = activeSchema(storeId, schemaId);
        long used = articles.countBySchema(schemaId);
        if (used > 0) {
            throw ApiException.conflict("Schemat ma artykuły (" + used + "). Najpierw wybierz im inny schemat.");
        }
        schema.delete(users.getReferenceById(userId));
    }

    @Transactional
    public ProductionBaseResponse restoreSchema(UUID storeId, UUID schemaId) {
        ProductionSchema schema = schemas.findByIdAndStoreId(schemaId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono schematu"));
        if (schema.isDeleted()) {
            if (schemas.nameTaken(storeId, schema.getName(), schemaId)) {
                throw ApiException.conflict("Jest już inny schemat o tej nazwie");
            }
            schema.restore();
        }
        return base(storeId);
    }

    @Transactional
    public ProductionBaseResponse createArticle(UUID storeId, ProductionArticleRequest request, UUID userId) {
        AppUser by = users.getReferenceById(userId);
        ProductionArticle article = new ProductionArticle(storeId, by);
        apply(storeId, article, request, null, by);
        articles.save(article);
        return base(storeId);
    }

    @Transactional
    public ProductionBaseResponse updateArticle(UUID storeId, UUID articleId, ProductionArticleRequest request,
            UUID userId) {
        ProductionArticle article = articles.findInStore(storeId, articleId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu"));
        apply(storeId, article, request, articleId, users.getReferenceById(userId));
        return base(storeId);
    }

    @Transactional
    public ProductionBaseResponse changeSchema(UUID storeId, ArticleSchemaRequest request, UUID userId) {
        ProductionSchema schema = activeSchema(storeId, request.schemaId());
        AppUser by = users.getReferenceById(userId);
        List<ProductionArticle> selected = articles.findActive(storeId, request.articleIds());
        if (selected.size() != request.articleIds().stream().distinct().count()) {
            throw ApiException.notFound("Nie znaleziono części artykułów. Odśwież stronę.");
        }
        selected.forEach(article -> article.changeSchema(schema, by));
        return base(storeId);
    }

    @Transactional
    public void deleteArticle(UUID storeId, UUID articleId, UUID userId) {
        ProductionArticle article = articles.findInStore(storeId, articleId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu"));
        article.delete(users.getReferenceById(userId));
    }

    @Transactional
    public ProductionBaseResponse restoreArticle(UUID storeId, UUID articleId) {
        ProductionArticle article = articles.findInStore(storeId, articleId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono artykułu"));
        if (article.isDeleted()) {
            if (article.getCode() != null && articles.codeTaken(storeId, article.getCode(), articleId)) {
                throw ApiException.conflict("Kod " + article.getCode() + " ma już inny artykuł");
            }
            if (article.getSchema().isDeleted()) {
                article.getSchema().restore();
            }
            article.restore();
        }
        return base(storeId);
    }

    /**
     * Wgrywa bazę z dawnego arkusza: artykuły po kodzie (bez kodu po nazwie) są dodawane albo nadpisywane, brakujące
     * w pliku zostają. Parametry każdego wiersza trafiają do schematu o tych samych wartościach, a gdy takiego nie ma,
     * powstaje nowy z nazwą z wartości.
     */
    @Transactional
    public ProductionImportResponse importBase(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do wgrania");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }
        List<ProductionBaseParser.Row> rows = parse(file);
        AppUser by = users.getReferenceById(userId);

        Map<ProcessParameters, ProductionSchema> byParameters = new HashMap<>();
        for (ProductionSchema schema : schemas.findActive(storeId)) {
            byParameters.putIfAbsent(schema.getParameters(), schema);
        }
        List<ProductionArticle> existing = articles.findActive(storeId);
        Map<String, ProductionArticle> byCode = existing.stream()
                .filter(article -> article.getCode() != null)
                .collect(Collectors.toMap(ProductionArticle::getCode, Function.identity()));
        Map<String, ProductionArticle> byName = existing.stream()
                .filter(article -> article.getCode() == null)
                .collect(Collectors.toMap(article -> article.getName().toUpperCase(Locale.ROOT), Function.identity(),
                        (first, second) -> first));

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int created = 0;
        for (ProductionBaseParser.Row row : rows) {
            ProductionSchema schema = byParameters.get(row.parameters());
            if (schema == null) {
                schema = schemas.save(new ProductionSchema(storeId, uniqueName(storeId, schemaName(row.parameters())),
                        row.parameters(), by));
                byParameters.put(row.parameters(), schema);
                created++;
            }
            ProductionArticle article = row.code() != null
                    ? byCode.get(row.code())
                    : byName.get(row.name().toUpperCase(Locale.ROOT));
            if (article == null) {
                article = new ProductionArticle(storeId, by);
                article.update(row.code(), row.name(), row.nomenclature(), schema, by);
                articles.save(article);
                added++;
            } else if (Objects.equals(article.getName(), row.name())
                    && Objects.equals(article.getNomenclature(), row.nomenclature())
                    && article.getSchema().getId().equals(schema.getId())) {
                unchanged++;
            } else {
                article.update(article.getCode(), row.name(), row.nomenclature(), schema, by);
                updated++;
            }
        }
        return new ProductionImportResponse(added, updated, unchanged, created);
    }

    /** Formularz do wydruku: wszystkie artykuły bazy alfabetycznie. */
    @Transactional(readOnly = true)
    public byte[] formPdf(UUID storeId) {
        Store store = store(storeId);
        List<ProductionPdf.FormLine> lines = articles.findActive(storeId).stream()
                .sorted((first, second) -> first.getName().compareToIgnoreCase(second.getName()))
                .map(article -> new ProductionPdf.FormLine(article.getName(), article.getCode()))
                .toList();
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Baza nie ma artykułów. Dodaj je albo wgraj bazę z Excela.");
        }
        return pdf.form(new ProductionPdf.Form(store.getNumber() + " · " + store.getName(), lines));
    }

    private void apply(UUID storeId, ProductionArticle article, ProductionArticleRequest request, UUID exceptId,
            AppUser by) {
        String code = request.code() == null || request.code().isBlank() ? null : request.code().strip();
        if (code != null && articles.codeTaken(storeId, code, exceptId)) {
            throw ApiException.conflict("Kod " + code + " ma już inny artykuł");
        }
        String nomenclature = request.nomenclature() == null || request.nomenclature().isBlank()
                ? null : request.nomenclature().strip();
        article.update(code, request.name().strip(), nomenclature, activeSchema(storeId, request.schemaId()), by);
    }

    private ProductionSchema activeSchema(UUID storeId, UUID schemaId) {
        return schemas.findByIdAndStoreId(schemaId, storeId)
                .filter(schema -> !schema.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono schematu"));
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }

    private String uniqueName(UUID storeId, String name) {
        String candidate = name;
        for (int i = 2; schemas.nameTaken(storeId, candidate, null); i++) {
            candidate = name + " (" + i + ")";
        }
        return candidate;
    }

    private List<ProductionBaseParser.Row> parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("production-base-", ".xlsx");
            file.transferTo(temp);
            return parser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać bazy kart produkcji", e);
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Nie udało się usunąć pliku tymczasowego {}", temp, e);
                }
            }
        }
    }

    private static ProcessParameters parameters(ProductionSchemaRequest request) {
        return new ProcessParameters(request.bakingTemperature(), request.internalTemperature(), request.coolingTime(),
                request.coolingTemperature(), request.shelfLifeDays());
    }

    /** Nazwa schematu z wartości, np. "Pieczenie 140°C · wewn. 75°C · schładzanie 20 do 4°C · 5 dni". */
    static String schemaName(ProcessParameters parameters) {
        List<String> parts = new ArrayList<>();
        if (parameters.bakingTemperature() != null) {
            parts.add("Pieczenie " + parameters.bakingTemperature() + "°C");
        }
        if (parameters.internalTemperature() != null) {
            parts.add("wewn. " + parameters.internalTemperature() + "°C");
        }
        if (parameters.coolingTime() != null || parameters.coolingTemperature() != null) {
            parts.add("schładzanie"
                    + (parameters.coolingTime() != null ? " " + parameters.coolingTime() : "")
                    + (parameters.coolingTemperature() != null ? " do " + parameters.coolingTemperature() + "°C" : ""));
        }
        if (parts.isEmpty()) {
            parts.add("Bez obróbki");
        }
        if (parameters.shelfLifeDays() != null) {
            parts.add(parameters.shelfLifeDays() + (parameters.shelfLifeDays() == 1 ? " dzień" : " dni"));
        }
        return String.join(" · ", parts);
    }

    /**
     * Pieczątka zakładu bez zapisanej: firma z GUS po NIP-ie sklepu (nazwa i adres), a bez niej sam adres sklepu.
     * Nazwa sklepu w aplikacji jest wewnętrzna, więc na pieczątkę nie trafia.
     */
    private String defaultStamp(Store store) {
        Optional<GusCompany> company = gus.ofStore(store);
        String address = company.map(GusCompany::address).orElse(store.getAddress());
        StringBuilder stamp = new StringBuilder(company.map(GusCompany::name).orElse(""));
        if (address != null && !address.isBlank()) {
            stamp.append('\n').append(address.strip());
        }
        if (store.getNip() != null) {
            String nip = store.getNip();
            stamp.append("\nNIP: ").append(nip.length() == 10
                    ? nip.substring(0, 3) + " " + nip.substring(3, 5) + " " + nip.substring(5, 7) + " " + nip.substring(7)
                    : nip);
        }
        return stamp.toString().strip();
    }
}
