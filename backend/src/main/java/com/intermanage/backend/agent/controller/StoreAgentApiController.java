package com.intermanage.backend.agent.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.agent.dto.AgentApi;
import com.intermanage.backend.agent.dto.AgentMacroApi;
import com.intermanage.backend.agent.dto.AgentReportFileResponse;
import com.intermanage.backend.agent.service.AgentEventService;
import com.intermanage.backend.agent.service.AgentFileQueueService;
import com.intermanage.backend.agent.service.AgentMacroService;
import com.intermanage.backend.agent.service.AgentMacroWaitService;
import com.intermanage.backend.agent.service.AgentReportService;
import com.intermanage.backend.agent.service.StoreAgentService;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.download.entity.DownloadFile;
import com.intermanage.backend.download.service.DownloadService;
import com.intermanage.backend.importguide.dto.ReportMacroResponse;
import com.intermanage.backend.importguide.entity.ImportReportKind;
import com.intermanage.backend.importguide.service.ReportMacroService;
import com.intermanage.backend.mea.dto.MeaAthosStatus;
import com.intermanage.backend.product.dto.PriceFileUploadResponse;
import com.intermanage.backend.store.entity.StoreFileQueueItem;
import com.intermanage.backend.store.service.StoreFileQueueService;

import jakarta.validation.Valid;

/**
 * API agenta sklepu: bez logowania i bez CSRF (wyjątek w {@code SecurityConfig}, jak czytniki kart), agent
 * przedstawia się kluczem z Ustawień sklepu w nagłówku {@code Authorization: Bearer <klucz>}. Każde żądanie odświeża
 * kontakt agenta i dzierżawę głównego agenta sklepu; nagłówek {@code X-Agent-Leader: true|false} w każdej odpowiedzi
 * mówi agentowi, czy ma pilnować folderów i aktualizować dane. Usunięty klucz = 403.
 *
 * <pre>
 * POST /api/store-agent/hello              {version, machineName, user} → foldery, pilnowane foldery
 * GET  /api/store-agent/file-queue/wait?timeout=25   long polling: pliki przydzielone agentowi albo pusta lista
 * GET  /api/store-agent/file-queue/{id}/file
 * POST /api/store-agent/file-queue/{id}/saved | /failed {error}
 * POST /api/store-agent/events             {events:[…]} → pliki, o które prosi serwer
 * POST /api/store-agent/price-files        multipart files (TRACEART, także .gz)
 * POST /api/store-agent/athos-history      multipart file, modified, partial (Histo.Log albo jego końcówka, także .gz)
 * POST /api/store-agent/reports            {folderKey, fileName, size, modified} → {id, chunkSize} (raport importu)
 * PUT  /api/store-agent/reports/{id}/chunks/{index}   surowe bajty kawałka, po kolei
 * POST /api/store-agent/reports/{id}/finish           202, import w tle
 * POST /api/store-agent/reports/removed    {folderKey, fileName, modified} (agent usunął wgrany raport z folderu)
 * GET  /api/store-agent/reports            ostatnie próby wgrania raportów (okno aktualizacji danych)
 * POST /api/store-agent/report-macro       {kinds, automatic} → makro aktualizacji raportów z Mercalys (pliki nie
 *                                          z dzisiaj); 409, gdy aktualizuje inny komputer albo agent nie jest główny
 * POST /api/store-agent/report-macro/finished   koniec aktualizacji (zdejmuje blokadę)
 * GET  /api/store-agent/update/file        paczka agenta z „Do pobrania” (ZIP; wersja i SHA-256 w odpowiedzi /hello)
 * POST /api/store-agent/macro-jobs/poll    {idleSeconds, locked, playerRunning, busy, playerVersion} co kilka sekund → {offer}
 *                                          (makro zlecone z przeglądarki, zaproponowane temu komputerowi; agent przed 1.9.0)
 * POST /api/store-agent/macro-jobs/wait    to samo + {handled, timeout}: long polling, odpowiedź, gdy komputer dostanie makro
 * POST /api/store-agent/macro-jobs/{id}/decline   pracownik anulował odliczanie: zlecenie idzie do kolejnego komputera
 * POST /api/store-agent/macro-jobs/{id}/start     koniec odliczania → treść makra (409: anulowane albo przekazane)
 * POST /api/store-agent/macro-jobs/{id}/files     multipart key, file (także .gz): plik zapisany przez makro, z listy
 *                                          {@code uploads} startu, przed wynikiem
 * POST /api/store-agent/macro-jobs/{id}/finish    {ok, message, codes} wynik odtwarzacza
 * </pre>
 */
@RestController
@RequestMapping("/api/store-agent")
public class StoreAgentApiController {

    private static final String BEARER = "Bearer ";
    public static final String LEADER_HEADER = "X-Agent-Leader";

    private final StoreAgentService agents;
    private final AgentFileQueueService waiting;
    private final AgentEventService events;
    private final StoreFileQueueService queue;
    private final AgentReportService reports;
    private final ReportMacroService macros;
    private final DownloadService downloads;
    private final AgentMacroService macroJobs;
    private final AgentMacroWaitService macroWait;

    public StoreAgentApiController(StoreAgentService agents, AgentFileQueueService waiting, AgentEventService events,
            StoreFileQueueService queue, AgentReportService reports, ReportMacroService macros,
            DownloadService downloads, AgentMacroService macroJobs, AgentMacroWaitService macroWait) {
        this.macroWait = macroWait;
        this.macroJobs = macroJobs;
        this.downloads = downloads;
        this.macros = macros;
        this.agents = agents;
        this.waiting = waiting;
        this.events = events;
        this.queue = queue;
        this.reports = reports;
    }

    @PostMapping("/hello")
    public AgentApi.HelloResponse hello(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @Valid @RequestBody AgentApi.Hello request) {
        return agents.hello(agent(authorization), request);
    }

    @GetMapping("/file-queue/wait")
    public DeferredResult<AgentApi.QueueFiles> await(@RequestHeader(value = HttpHeaders.AUTHORIZATION,
            required = false) String authorization, @RequestParam(defaultValue = "25") int timeout) {
        return waiting.await(agent(authorization), timeout);
    }

    @GetMapping("/file-queue/{id}/file")
    public ResponseEntity<byte[]> file(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @PathVariable UUID id) {
        StoreAgentService.AgentContext agent = agent(authorization);
        StoreFileQueueItem item = queue.file(agent.storeId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(item.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Folder-Key", item.getFolderKey())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(item.getContent());
    }

    @PostMapping("/file-queue/{id}/saved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saved(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id) {
        StoreAgentService.AgentContext agent = agent(authorization);
        queue.savedByAgent(agent.storeId(), id, agent.agentId());
    }

    @PostMapping("/file-queue/{id}/failed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void failed(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id, @Valid @RequestBody(required = false) AgentApi.Failed request) {
        StoreAgentService.AgentContext agent = agent(authorization);
        queue.failedByAgent(agent.storeId(), id, agent.agentId(), request == null ? null : request.error());
    }

    @PostMapping("/events")
    public AgentApi.EventsResponse events(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @Valid @RequestBody AgentApi.Events request) {
        return events.handle(agent(authorization), request.events());
    }

    @PostMapping(value = "/price-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PriceFileUploadResponse priceFiles(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @RequestParam("files") List<MultipartFile> files) {
        return events.priceFiles(agent(authorization), files);
    }

    @PostMapping(value = "/athos-history", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MeaAthosStatus athosHistory(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @RequestParam("file") MultipartFile file,
            @RequestParam(value = "modified", required = false) Long modified,
            @RequestParam(value = "partial", defaultValue = "false") boolean partial) {
        return events.athosHistory(agent(authorization), file, modified, partial);
    }

    @PostMapping("/reports")
    public AgentApi.ReportUpload startReport(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @Valid @RequestBody AgentApi.ReportStart request) {
        return reports.start(agent(authorization), request);
    }

    @PutMapping(path = "/reports/{id}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportChunk(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id, @PathVariable int index, @RequestBody byte[] bytes) {
        reports.chunk(agent(authorization), id, index, bytes);
    }

    @PostMapping("/reports/{id}/finish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void finishReport(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @PathVariable UUID id) {
        reports.finish(agent(authorization), id);
    }

    @PostMapping("/reports/removed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportRemoved(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @Valid @RequestBody AgentApi.ReportRemoved request) {
        reports.removed(agent(authorization), request);
    }

    /**
     * Makro aktualizacji raportów z Mercalys: agent przy starcie komputera, gdy pliki raportów w folderach nie są
     * z dzisiaj. Hasło Mercalys dokłada agent do pierwszego polecenia ({@code openLine}).
     */
    @PostMapping("/report-macro")
    public ReportMacroResponse reportMacro(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @Valid @RequestBody AgentApi.ReportMacroRequest request) {
        StoreAgentService.AgentContext agent = agent(authorization);
        List<ImportReportKind> kinds = new ArrayList<>();
        for (String kind : request.kinds() == null ? List.<String>of() : request.kinds()) {
            ImportReportKind parsed = AgentReportService.kindOf(AgentReportService.PREFIX + kind);
            if (parsed == null) {
                throw ApiException.badRequest("Nieznany raport: " + kind);
            }
            kinds.add(parsed);
        }
        agents.startUpdate(agent, !Boolean.FALSE.equals(request.automatic()));
        try {
            return macros.build(agent.storeId(), kinds);
        } catch (RuntimeException e) {
            agents.finishUpdate(agent);
            throw e;
        }
    }

    /** Koniec aktualizacji danych z Mercalys na tym komputerze (udanej albo nie). */
    @PostMapping("/report-macro/finished")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportMacroFinished(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization) {
        agents.finishUpdate(agent(authorization));
    }

    /** Ostatnie próby wgrania raportów (okno aktualizacji czeka, aż serwer potwierdzi wgranie). */
    @GetMapping("/reports")
    public List<AgentReportFileResponse> recentReports(@RequestHeader(value = HttpHeaders.AUTHORIZATION,
            required = false) String authorization) {
        return reports.recent(agent(authorization).storeId(), 100);
    }

    /**
     * Paczka agenta do automatycznej aktualizacji (ZIP). Agent sprawdza SHA-256 i rozmiar z /hello, a podpis exe
     * przed instalacją. Przy wolnym łączu sklepu paczka (kilka MB) idzie kilkanaście sekund.
     */
    @GetMapping("/update/file")
    public ResponseEntity<byte[]> updateFile(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization) {
        agent(authorization);
        DownloadFile file = downloads.agentUpdateFile();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Sha256", file.getSha256())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.getContent());
    }

    @PostMapping("/macro-jobs/poll")
    public AgentMacroApi.PollResponse pollMacros(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @Valid @RequestBody AgentMacroApi.Poll request) {
        return macroJobs.poll(agent(authorization), request);
    }

    @PostMapping("/macro-jobs/wait")
    public DeferredResult<AgentMacroApi.PollResponse> awaitMacro(@RequestHeader(value = HttpHeaders.AUTHORIZATION,
            required = false) String authorization, @Valid @RequestBody AgentMacroApi.Wait request) {
        return macroWait.await(agent(authorization), request);
    }

    @PostMapping("/macro-jobs/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void declineMacro(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id, @Valid @RequestBody(required = false) AgentMacroApi.Decline request) {
        macroJobs.decline(agent(authorization), id);
    }

    @PostMapping("/macro-jobs/{id}/start")
    public AgentMacroApi.Start startMacro(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @PathVariable UUID id) {
        return macroJobs.start(agent(authorization), id);
    }

    @PostMapping(value = "/macro-jobs/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void macroFile(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id, @RequestParam("key") String key, @RequestParam("file") MultipartFile file) {
        macroJobs.file(agent(authorization), id, key, file);
    }

    @PostMapping("/macro-jobs/{id}/finish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finishMacro(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id, @Valid @RequestBody AgentMacroApi.Finish request) {
        macroJobs.finish(agent(authorization), id, request);
    }

    private StoreAgentService.AgentContext agent(String authorization) {
        StoreAgentService.AgentContext agent = agents.authenticate(authorization != null
                && authorization.startsWith(BEARER) ? authorization.substring(BEARER.length()) : null);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getResponse() != null) {
            attributes.getResponse().setHeader(LEADER_HEADER, String.valueOf(agent.leader()));
        }
        return agent;
    }
}
