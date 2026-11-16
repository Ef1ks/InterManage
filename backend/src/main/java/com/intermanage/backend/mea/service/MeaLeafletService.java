package com.intermanage.backend.mea.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.mea.component.MeaLeafletParser;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.intermanage.backend.mea.dto.MeaDocumentResponse;
import com.intermanage.backend.mea.dto.MeaLeafletSummary;
import com.intermanage.backend.mea.entity.MeaLeafletImport;
import com.intermanage.backend.mea.entity.MeaLeafletLine;
import com.intermanage.backend.mea.repository.MeaLeafletImportRepository;

/**
 * Opisy gazetek z centrali (forma promocji, megapromocja, limit, cena dla klienta), wspólne dla sklepów jak formularze
 * MEA. Pozycja formularza MEA dostaje pozycję opisu po ITM 8 PROMO.
 */
@Service
public class MeaLeafletService {

    private static final Logger log = LoggerFactory.getLogger(MeaLeafletService.class);
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    /** Tyle najnowszych gazetek z listy portalu sprawdzamy (eMEA pokazuje dziesięć). */
    static final int PORTAL_LEAFLETS = 10;

    private final MeaLeafletImportRepository imports;
    private final MeaLeafletParser parser;
    private final XlsxReader xlsxReader;
    private final AppUserRepository users;
    private final MeaService mea;
    private final MurphysoftClient client;
    private final TransactionTemplate transaction;
    /** Pliki portalu (ścieżka|data zmiany), których nie dało się odczytać; do restartu ich nie pobieramy. */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public MeaLeafletService(MeaLeafletImportRepository imports, MeaLeafletParser parser, XlsxReader xlsxReader,
            AppUserRepository users, MeaService mea, MurphysoftClient client,
            PlatformTransactionManager transactionManager) {
        this.imports = imports;
        this.parser = parser;
        this.xlsxReader = xlsxReader;
        this.users = users;
        this.mea = mea;
        this.client = client;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<MeaLeafletSummary> list() {
        return imports.findTop30ByReplacedAtIsNullOrderByYearDescWeekDesc().stream()
                .map(MeaLeafletService::summary).toList();
    }

    /**
     * Wgranie opisu ręcznie (zapas, gdy portal nie działa); ta sama gazetka (rok i numer) wgrana ponownie zastępuje
     * poprzednią (znacznik).
     */
    @Transactional
    public MeaLeafletSummary upload(MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() == null ? "gazetka.xlsx" : file.getOriginalFilename();
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do wgrania");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej 20 MB)");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        }
        MeaLeafletParser.Parsed parsed;
        try {
            parsed = parser.parse(sheet(bytes, fileName));
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        }
        AppUser by = users.getReferenceById(userId);
        return summary(save(parsed, new MeaLeafletImport(parsed.year(), parsed.week(), fileName, by), by));
    }

    /**
     * Sprawdza portal ({@code /api/cad/}) sesją sklepu i pobiera opisy gazetek, których jeszcze nie mamy (po ścieżce
     * i dacie zmiany pliku), z {@value #PORTAL_LEAFLETS} najnowszych gazetek. Plik, którego nie da się odczytać,
     * zostaje pominięty do restartu (nie pobieramy go co przebieg). Zwraca liczbę pobranych.
     */
    public int syncFromPortal(UUID storeId) {
        MeaService.Session session = mea.session(storeId);
        JsonNode list = client.cadList(session.shopId(), session.token());
        List<PortalFile> files = new ArrayList<>();
        for (JsonNode leaflet : list.path("gazetki")) {
            JsonNode file = leaflet.path("file").path(0);
            String path = file.path("path").asString("");
            if (!path.isBlank()) {
                files.add(new PortalFile(leaflet.path("name").asString(""), position(leaflet),
                        file.path("name").asString(""), path, file.path("update_date").asString("")));
            }
        }
        // Najnowsze gazetki mają najwyższą pozycję (88 = „Gazetka 53/2026”); po nazwie „1/2027” wypadłaby za „53/2026”.
        files.sort(Comparator.comparingLong(PortalFile::position).reversed());
        int downloaded = 0;
        for (PortalFile file : files.subList(0, Math.min(PORTAL_LEAFLETS, files.size()))) {
            String key = file.path() + "|" + file.updatedAt();
            if (failed.contains(key) || Boolean.TRUE.equals(transaction.execute(
                    status -> imports.existsBySourcePathAndSourceUpdatedAt(file.path(), file.updatedAt())))) {
                continue;
            }
            String fileName = file.name().isBlank() ? file.leaflet() + ".xlsx" : file.name();
            MeaLeafletParser.Parsed parsed;
            try {
                parsed = parser.parse(sheet(client.cadFile(session.shopId(), session.token(), file.path()),
                        fileName));
            } catch (InvalidReportException | ApiException exception) {
                log.warn("Opis gazetki {} z portalu nieczytelny: {}", fileName, exception.getMessage());
                failed.add(key);
                continue;
            }
            transaction.executeWithoutResult(status -> save(parsed, MeaLeafletImport.fromPortal(parsed.year(),
                    parsed.week(), fileName, file.path(), file.updatedAt()), null));
            downloaded++;
        }
        return downloaded;
    }

    /**
     * Opis gazetki z listy portalu: nazwa gazetki, jej pozycja i pierwszy plik (jak w eMEA). Portal podaje np.
     * {@code {"id":"5983","name":"Gazetka 50/2026 Wszystkie sklepy","position":"85","file":[{"name":"Gazetka nr
     * 50_2026 BOŻE NARODZENIE 3.xlsx","update_date":"2026-11-13 13:24:03","path":"htmldownload/…"}]}}, a gazetka
     * jeszcze bez opisu nie ma pola {@code file}.
     */
    private record PortalFile(String leaflet, long position, String name, String path, String updatedAt) {
    }

    private static long position(JsonNode leaflet) {
        try {
            return Long.parseLong(leaflet.path("position").asString("0").strip());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** Zapis opisu; obowiązujący opis tej samej gazetki dostaje znacznik zastąpienia. */
    private MeaLeafletImport save(MeaLeafletParser.Parsed parsed, MeaLeafletImport leaflet, AppUser by) {
        imports.findByYearAndWeekAndReplacedAtIsNull((short) parsed.year(), (short) parsed.week())
                .ifPresent(previous -> {
                    previous.replace(by);
                    // Aktywna może być tylko jedna gazetka o tym numerze (indeks unikalny).
                    imports.flush();
                });
        parsed.lines().forEach(line -> leaflet.addLine(new MeaLeafletLine(line)));
        imports.saveAndFlush(leaflet);
        log.info("Opis gazetki {}/{} ({}): {} pozycji", parsed.week(), parsed.year(),
                leaflet.isFromPortal() ? "portal" : "wgrany", parsed.lines().size());
        return leaflet;
    }

    /**
     * Pozycja opisu dla każdego ITM 8 PROMO z formularza MEA {@code number}. Ten sam kod w kilku gazetkach: najpierw
     * pozycja z tym numerem MEA (bywa „błędnie podpięty do MEA …”), potem z najnowszej gazetki.
     */
    @Transactional(readOnly = true)
    public Map<String, MeaDocumentResponse.Leaflet> forDocument(String number, Collection<String> promoItm8s) {
        Map<String, MeaLeafletLine> chosen = new HashMap<>();
        if (promoItm8s.isEmpty()) {
            return Map.of();
        }
        for (MeaLeafletLine line : imports.findActiveLines(promoItm8s)) {
            MeaLeafletLine current = chosen.get(line.getPromoItm8());
            if (current == null || (!number.equals(current.getMeaNumber()) && number.equals(line.getMeaNumber()))) {
                chosen.put(line.getPromoItm8(), line);
            }
        }
        Map<String, MeaDocumentResponse.Leaflet> result = new HashMap<>();
        chosen.forEach((itm8, line) -> result.put(itm8, leaflet(line)));
        return result;
    }

    private static MeaDocumentResponse.Leaflet leaflet(MeaLeafletLine line) {
        MeaLeafletImport leaflet = line.getLeafletImport();
        return new MeaDocumentResponse.Leaflet(leaflet.getYear(), leaflet.getWeek(), line.getSection(),
                line.getDateFrom(), line.getDateTo(), line.getMeaNumber(),
                line.getPromoForm() == null ? List.of() : List.of(line.getPromoForm().split("\n")),
                line.getMegaKind(), line.getMegaVariant(), line.getMegaQuantity(), line.getMegaPrice(),
                line.getPurchasePrice(), line.getClientPrice(), line.getClientLimit(), line.getDeliveryForm());
    }

    private static MeaLeafletSummary summary(MeaLeafletImport leaflet) {
        return new MeaLeafletSummary(leaflet.getId(), leaflet.getYear(), leaflet.getWeek(), leaflet.getFileName(),
                leaflet.getLineCount(), leaflet.getUploadedAt(),
                leaflet.getUploadedBy() == null ? null : leaflet.getUploadedBy().getDisplayName(),
                leaflet.isFromPortal());
    }

    private List<List<String>> sheet(byte[] bytes, String fileName) {
        try {
            if (bytes.length < ZIP_SIGNATURE.length
                    || !Arrays.equals(bytes, 0, ZIP_SIGNATURE.length, ZIP_SIGNATURE, 0, ZIP_SIGNATURE.length)) {
                throw ApiException.badRequest("Plik " + fileName + " to nie skoroszyt .xlsx. Otwórz go w Excelu "
                        + "i zapisz jako skoroszyt .xlsx.");
            }
            Path temp = Files.createTempFile("mea-leaflet-", ".xlsx");
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
