package com.intermanage.backend.collector.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.collector.component.CollectorProgram;
import com.intermanage.backend.collector.service.CollectorCatalogService;
import com.intermanage.backend.collector.service.CollectorListService;
import com.intermanage.backend.collector.service.StoreCollectorService;
import com.intermanage.backend.collector.service.StoreCollectorService.CollectorContext;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;

/**
 * API kolektora (Windows CE, {@code collector/}): bez logowania i CSRF (wyjątek w {@code SecurityConfig}), kolektor
 * przedstawia się kluczem {@code Authorization: Bearer <klucz>}. Odpowiedzi to zwykły tekst {@code klucz=wartość} w
 * liniach, bo Compact Framework nie ma JSON-a.
 *
 * <pre>
 * POST /api/collector/pair     code, device, version (formularz) → key, store, storeName, name, time
 * GET  /api/collector/status   → time, catalog (wersja danych), store, storeName, name
 * GET  /api/collector/catalog  → plik kartoteki spakowany zlib; nagłówki X-Catalog-Version, X-Catalog-Size, X-Catalog-Sha256
 * POST /api/collector/synced   version (formularz): kolektor zapisał dane tej wersji
 * POST /api/collector/lists    id, started, lines (formularz): lista z Protokołów; ta sama drugi raz jest pomijana
 * GET  /api/collector/program  → version, files i przy każdym pliku .size i .sha256 (nowa wersja programu)
 * GET  /api/collector/program/{plik}?version=  plik tej wersji (409, gdy serwer ma już inną)
 * GET  /api/collector/setup    instalator, bez klucza (pobiera się go na komputerze, zanim kolektor jest połączony)
 * </pre>
 */
@RestController
@RequestMapping("/api/collector")
public class CollectorApiController {

    private static final String BEARER = "Bearer ";
    /** Wersja programu kolektora w każdym żądaniu. */
    private static final String VERSION_HEADER = "X-Collector-Version";
    /** Czas sklepu: po wyczyszczeniu kolektor ma zły zegar i ustawia go z tej odpowiedzi. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final StoreCollectorService collectors;
    private final CollectorCatalogService catalog;
    private final CollectorProgram program;
    private final CollectorListService lists;

    public CollectorApiController(StoreCollectorService collectors, CollectorCatalogService catalog,
            CollectorProgram program, CollectorListService lists) {
        this.lists = lists;
        this.program = program;
        this.collectors = collectors;
        this.catalog = catalog;
    }

    @PostMapping("/pair")
    public ResponseEntity<String> pair(@RequestParam String code, @RequestParam(required = false) String device,
            @RequestParam(required = false) String version) {
        StoreCollectorService.Pairing pairing = collectors.pair(code, device, version);
        Map<String, String> values = describe(pairing.collector());
        values.put("key", pairing.key());
        return text(values);
    }

    @GetMapping("/status")
    public ResponseEntity<String> status(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = VERSION_HEADER, required = false) String version) {
        CollectorContext collector = collector(authorization, version);
        Map<String, String> values = describe(collector);
        values.put("catalog", catalog.version(collector.storeId()));
        program.version().ifPresent(latest -> values.put("program", latest));
        return text(values);
    }

    @GetMapping("/catalog")
    public ResponseEntity<byte[]> catalog(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = VERSION_HEADER, required = false) String version) {
        CollectorCatalogService.CatalogFile file = catalog.file(collector(authorization, version).storeId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                // Plik jest już spakowany, a kolektor liczy postęp z Content-Length.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
                .header("X-Catalog-Version", file.version())
                .header("X-Catalog-Size", String.valueOf(file.plainSize()))
                .header("X-Catalog-Sha256", file.sha256())
                .body(file.content());
    }

    @PostMapping("/synced")
    public ResponseEntity<String> synced(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = VERSION_HEADER, required = false) String programVersion,
            @RequestParam String version) {
        CollectorContext collector = collector(authorization, programVersion);
        collectors.synced(collector, version);
        return text(describe(collector));
    }

    @PostMapping("/lists")
    public ResponseEntity<String> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = VERSION_HEADER, required = false) String programVersion,
            @RequestParam UUID id, @RequestParam(required = false) String started, @RequestParam String lines) {
        CollectorContext collector = collector(authorization, programVersion);
        lists.receive(collector, id, started, lines);
        return text(describe(collector));
    }

    @GetMapping("/program")
    public ResponseEntity<String> program(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = VERSION_HEADER, required = false) String version) {
        collector(authorization, version);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("version", program.version()
                .orElseThrow(() -> ApiException.notFound("Nie ma programu do pobrania")));
        values.put("files", String.join(",", program.files().keySet()));
        program.files().forEach((name, file) -> {
            values.put(name + ".size", String.valueOf(file.content().length));
            values.put(name + ".sha256", file.sha256());
        });
        return text(values);
    }

    @GetMapping("/program/{name}")
    public ResponseEntity<byte[]> programFile(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = VERSION_HEADER, required = false) String programVersion,
            @PathVariable String name, @RequestParam String version) {
        collector(authorization, programVersion);
        if (!program.version().filter(version::equals).isPresent()) {
            throw ApiException.conflict("Jest już inna wersja programu");
        }
        CollectorProgram.ProgramFile file = program.files().get(name);
        if (file == null) {
            throw ApiException.notFound("Nie ma takiego pliku programu");
        }
        return binary(file.content()).body(file.content());
    }

    @GetMapping("/setup")
    public ResponseEntity<byte[]> setup() {
        byte[] content = program.setup().orElseThrow(() -> ApiException.notFound("Nie ma instalatora"));
        return binary(content)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(CollectorProgram.SETUP).build().toString())
                .body(content);
    }

    private static ResponseEntity.BodyBuilder binary(byte[] content) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(content.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform");
    }

    private CollectorContext collector(String authorization, String version) {
        return collectors.authenticate(authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()) : null, version);
    }

    private static Map<String, String> describe(CollectorContext collector) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("time", TIME.format(LocalDateTime.now(StoreTime.ZONE)));
        values.put("store", collector.storeNumber());
        values.put("storeName", collector.storeName());
        values.put("name", collector.name());
        return values;
    }

    private static ResponseEntity<String> text(Map<String, String> values) {
        return ResponseEntity.ok().contentType(TEXT).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(values.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue().replaceAll("[\\r\\n]+", " "))
                        .collect(Collectors.joining("\n")) + "\n");
    }
}
