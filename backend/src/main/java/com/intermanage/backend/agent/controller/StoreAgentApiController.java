package com.intermanage.backend.agent.controller;

import java.nio.charset.StandardCharsets;
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
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.agent.dto.AgentApi;
import com.intermanage.backend.agent.service.AgentEventService;
import com.intermanage.backend.agent.service.AgentFileQueueService;
import com.intermanage.backend.agent.service.AgentReportService;
import com.intermanage.backend.agent.service.StoreAgentService;
import com.intermanage.backend.mea.dto.MeaAthosStatus;
import com.intermanage.backend.product.dto.PriceFileUploadResponse;
import com.intermanage.backend.store.entity.StoreFileQueueItem;
import com.intermanage.backend.store.service.StoreFileQueueService;

import jakarta.validation.Valid;

/**
 * API agenta sklepu: bez logowania i bez CSRF (wyjątek w {@code SecurityConfig}, jak czytniki kart), agent
 * przedstawia się kluczem z Ustawień sklepu w nagłówku {@code Authorization: Bearer <klucz>}. Każde żądanie odświeża
 * kontakt agenta; usunięty klucz = 403.
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
 * </pre>
 */
@RestController
@RequestMapping("/api/store-agent")
public class StoreAgentApiController {

    private static final String BEARER = "Bearer ";

    private final StoreAgentService agents;
    private final AgentFileQueueService waiting;
    private final AgentEventService events;
    private final StoreFileQueueService queue;
    private final AgentReportService reports;

    public StoreAgentApiController(StoreAgentService agents, AgentFileQueueService waiting, AgentEventService events,
            StoreFileQueueService queue, AgentReportService reports) {
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

    private StoreAgentService.AgentContext agent(String authorization) {
        return agents.authenticate(authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()) : null);
    }
}
