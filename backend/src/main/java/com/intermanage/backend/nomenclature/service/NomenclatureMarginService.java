package com.intermanage.backend.nomenclature.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.nomenclature.component.NomenclatureMarginParser;
import com.intermanage.backend.nomenclature.dto.NomenclatureMarginImportResponse;
import com.intermanage.backend.nomenclature.dto.NomenclatureMarginResponse;
import com.intermanage.backend.nomenclature.dto.NomenclatureMarginsRequest;
import com.intermanage.backend.nomenclature.dto.ParsedMarginFile;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.nomenclature.entity.NomenclatureMargin;
import com.intermanage.backend.nomenclature.mapper.NomenclatureMapper;
import com.intermanage.backend.nomenclature.repository.NomenclatureMarginRepository;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.product.component.ProductReportParser;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Marża wymagana na nomenklaturze sklepu (Ustawienia sklepu): na rejonie, rodzinie albo podrodzinie, obowiązuje
 * najbardziej szczegółowa. Wprowadzanie danych czerwieni marżę artykułu poniżej progu i liczy cenę sugerowaną.
 * Marże da się wpisać na ekranie albo wgrać z pliku Excel przygotowanego z szablonu ze wszystkimi pozycjami.
 */
@Service
public class NomenclatureMarginService {

    private static final Logger log = LoggerFactory.getLogger(NomenclatureMarginService.class);

    private final NomenclatureRepository nomenclatures;
    private final NomenclatureMarginRepository margins;
    private final NomenclatureMarginParser parser;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final NomenclatureMapper mapper;
    private final XlsxWriter xlsxWriter;

    public NomenclatureMarginService(NomenclatureRepository nomenclatures, NomenclatureMarginRepository margins,
            NomenclatureMarginParser parser, StoreRepository stores, AppUserRepository users,
            NomenclatureMapper mapper, XlsxWriter xlsxWriter) {
        this.nomenclatures = nomenclatures;
        this.margins = margins;
        this.parser = parser;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
        this.xlsxWriter = xlsxWriter;
    }

    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    /** Cała nomenklatura sklepu w kolejności kodów z marżą ustawioną i obowiązującą. */
    @Transactional(readOnly = true)
    public List<NomenclatureMarginResponse> list(UUID storeId) {
        Map<String, NomenclatureMargin> byCode = byCode(storeId);
        Map<String, BigDecimal> own = ownMargins(byCode);
        return nomenclatures.findAllByStore(storeId).stream()
                .map(item -> mapper.toMarginResponse(item, byCode.get(item.getCode()), effective(own, item.getCode())))
                .toList();
    }

    /**
     * Marża obowiązująca na każdej pozycji nomenklatury sklepu, która ją ma (własna albo z rodziny / rejonu).
     * Do wprowadzania danych.
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> effectiveByCode(UUID storeId) {
        Map<String, BigDecimal> own = ownMargins(byCode(storeId));
        if (own.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> result = new HashMap<>();
        for (Nomenclature item : nomenclatures.findAllByStore(storeId)) {
            BigDecimal value = effective(own, item.getCode());
            if (value != null) {
                result.put(item.getCode(), value);
            }
        }
        return result;
    }

    /** Zapisuje podane pozycje (pusta wartość czyści próg); pozostałe zostają bez zmian. */
    @Transactional
    public List<NomenclatureMarginResponse> save(UUID storeId, NomenclatureMarginsRequest request, UUID userId) {
        Map<String, BigDecimal> values = new HashMap<>();
        for (NomenclatureMarginsRequest.Item item : request.margins()) {
            // Skala jak w bazie, żeby odpowiedź po zapisie wyglądała tak samo jak po odczycie.
            values.put(item.code(), item.marginPercent() == null ? null : item.marginPercent().setScale(2));
        }
        List<String> unknown = unknownCodes(storeId, values.keySet());
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Nie ma takiej nomenklatury w sklepie: " + String.join(", ", unknown));
        }
        apply(storeId, values, userId);
        return list(storeId);
    }

    /**
     * Plik marż z Excela: musi mieć każdą pozycję nomenklatury sklepu (jak plik do uzupełnienia), więc zastępuje
     * wszystkie progi naraz; pusta komórka czyści próg.
     */
    @Transactional
    public NomenclatureMarginImportResponse importFile(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do wgrania");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }
        Map<String, BigDecimal> values = parse(file).margins();

        List<Nomenclature> all = nomenclatures.findAllByStore(storeId);
        if (all.isEmpty()) {
            throw ApiException.conflict("Sklep nie ma jeszcze nomenklatury. Wgraj ją w Imporcie danych.");
        }
        List<String> unknown = unknownCodes(storeId, values.keySet());
        if (!unknown.isEmpty()) {
            throw rowErrors("W pliku są kody spoza nomenklatury sklepu (" + unknown.size()
                    + "). Pobierz nowy plik do uzupełnienia.", unknown.stream().map(code -> "Nieznany kod " + code));
        }
        List<Nomenclature> missing = all.stream().filter(item -> !values.containsKey(item.getCode())).toList();
        if (!missing.isEmpty()) {
            throw rowErrors("W pliku brakuje pozycji nomenklatury (" + missing.size() + " z " + all.size()
                    + "). Plik musi mieć wszystkie pozycje: pobierz nowy plik do uzupełnienia.",
                    missing.stream().map(item -> "Brak " + item.getCode() + " " + item.getName()));
        }
        int changed = apply(storeId, values, userId);
        int withMargin = (int) values.values().stream().filter(value -> value != null).count();
        log.info("Marże wymagane sklepu {} z pliku {}: zmienione {}, z marżą {}", storeId, fileName, changed,
                withMargin);
        return new NomenclatureMarginImportResponse(changed, withMargin);
    }

    /** Plik do uzupełnienia: wszystkie pozycje nomenklatury z obecnymi marżami, kolumna marży do wpisania. */
    @Transactional(readOnly = true)
    public ExportFile template(UUID storeId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        List<Nomenclature> all = nomenclatures.findAllByStore(storeId);
        if (all.isEmpty()) {
            throw ApiException.conflict("Sklep nie ma jeszcze nomenklatury. Wgraj ją w Imporcie danych.");
        }
        Map<String, BigDecimal> own = ownMargins(byCode(storeId));
        List<XlsxWriter.Row> rows = new ArrayList<>();
        for (Nomenclature item : all) {
            boolean region = item.getLevel() == NomenclatureLevel.REGION;
            BigDecimal margin = own.get(item.getCode());
            rows.add(XlsxWriter.Row.of(
                    region ? XlsxWriter.Cell.bold(item.getCode()) : item.getCode(),
                    levelLabel(item.getLevel()),
                    region ? XlsxWriter.Cell.bold(item.getName()) : item.getName(),
                    margin));
        }
        byte[] content = xlsxWriter.writeRows("Marże", List.of(
                new XlsxWriter.Column(NomenclatureMarginParser.CODE_HEADER, 10),
                new XlsxWriter.Column("Poziom", 12),
                new XlsxWriter.Column("Nazwa", 45),
                new XlsxWriter.Column(NomenclatureMarginParser.MARGIN_HEADER, 10)), rows);
        return new ExportFile("marze_wymagane_" + store.getNumber() + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    /** Zapisuje progi; zwraca liczbę zmienionych pozycji. */
    private int apply(UUID storeId, Map<String, BigDecimal> values, UUID userId) {
        Map<String, NomenclatureMargin> existing = byCode(storeId);
        Store store = stores.getReferenceById(storeId);
        AppUser by = userId != null ? users.getReferenceById(userId) : null;
        List<NomenclatureMargin> toSave = new ArrayList<>();
        int changed = 0;
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            NomenclatureMargin margin = existing.get(entry.getKey());
            if (margin == null) {
                if (entry.getValue() == null) {
                    continue;
                }
                margin = new NomenclatureMargin(store, entry.getKey());
            }
            if (margin.update(entry.getValue(), by)) {
                toSave.add(margin);
                changed++;
            }
        }
        margins.saveAll(toSave);
        return changed;
    }

    private ParsedMarginFile parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("nomenclature-margins-", ".xlsx");
            file.transferTo(temp);
            return parser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku marż", e);
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

    private static ApiException rowErrors(String message, Stream<String> errors) {
        List<String> all = errors.toList();
        return ApiException.badRequest(message)
                .withProperty("rowErrors", all.stream().limit(ProductReportParser.MAX_REPORTED_ERRORS).toList())
                .withProperty("rowErrorCount", all.size());
    }

    private List<String> unknownCodes(UUID storeId, Collection<String> codes) {
        var known = nomenclatures.findByStoreIdAndCodeIn(storeId, codes).stream()
                .map(Nomenclature::getCode).collect(Collectors.toSet());
        return codes.stream().filter(code -> !known.contains(code)).sorted().toList();
    }

    private Map<String, NomenclatureMargin> byCode(UUID storeId) {
        return margins.findByStoreId(storeId).stream()
                .collect(Collectors.toMap(NomenclatureMargin::getCode, Function.identity()));
    }

    private static Map<String, BigDecimal> ownMargins(Map<String, NomenclatureMargin> byCode) {
        Map<String, BigDecimal> own = new HashMap<>();
        byCode.forEach((code, margin) -> {
            if (margin.getMarginPercent() != null) {
                own.put(code, margin.getMarginPercent());
            }
        });
        return own;
    }

    /** Najbardziej szczegółowa ustawiona: podrodzina, potem rodzina, potem rejon. */
    private static BigDecimal effective(Map<String, BigDecimal> own, String code) {
        for (int length = code.length(); length >= 2; length -= 2) {
            BigDecimal value = own.get(code.substring(0, length));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String levelLabel(NomenclatureLevel level) {
        return switch (level) {
            case REGION -> "Rejon";
            case FAMILY -> "Rodzina";
            case SUBFAMILY -> "Podrodzina";
        };
    }
}
