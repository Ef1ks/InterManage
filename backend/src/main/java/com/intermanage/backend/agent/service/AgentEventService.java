package com.intermanage.backend.agent.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.agent.dto.AgentApi;
import com.intermanage.backend.common.component.CompressedUploads;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.PriceControlFileItem;
import com.intermanage.backend.dataentry.dto.PriceControlFiles;
import com.intermanage.backend.dataentry.dto.PriceControlSeenRequest;
import com.intermanage.backend.dataentry.service.DataEntryPriceControlService;
import com.intermanage.backend.mea.dto.MeaAthosStatus;
import com.intermanage.backend.mea.service.MeaAthosService;
import com.intermanage.backend.product.dto.PriceFileUploadResponse;
import com.intermanage.backend.product.service.PriceFileService;

/**
 * Zdarzenia z folderów sklepu od agenta i pliki, o które serwer prosi. Wszystko idzie do tej samej logiki, z której
 * korzysta przeglądarka (jest idempotentna, więc agent i przeglądarka mogą się chwilowo nakładać):
 * <ul>
 * <li>TRACEART: nowe nazwy → {@link PriceFileService#pending}, czyli prośba o pliki, których sklep jeszcze nie ma;
 * <li>CTRLPRIX: nazwa z końcówką .TraiteN → {@link DataEntryPriceControlService#seen} (program sklepu wczytał
 * zmianę cen), z wiadomością na żywo {@code price-control};
 * <li>ATHOS: zmieniony {@code Histo.Log} nowszy niż ostatnio wczytany → prośba o plik.
 * </ul>
 */
@Service
public class AgentEventService {

    private static final Pattern CTRLPRIX = Pattern.compile("^(CTRLPRIX_.+)\\.traite(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final int PENDING_CHUNK = 1000;
    /** Limity rozpakowanych plików (jak przy wysyłce z przeglądarki). */
    private static final long MAX_PRICE_FILE_BYTES = 30L * 1024 * 1024;
    private static final long MAX_ATHOS_BYTES = 50L * 1024 * 1024;

    private final PriceFileService priceFiles;
    private final DataEntryPriceControlService priceControl;
    private final MeaAthosService athos;
    private final LiveEvents live;

    public AgentEventService(PriceFileService priceFiles, DataEntryPriceControlService priceControl,
            MeaAthosService athos, LiveEvents live) {
        this.priceFiles = priceFiles;
        this.priceControl = priceControl;
        this.athos = athos;
        this.live = live;
    }

    public AgentApi.EventsResponse handle(StoreAgentService.AgentContext agent, List<AgentApi.Event> events) {
        UUID storeId = agent.storeId();
        List<String> traceart = new ArrayList<>();
        Map<String, Integer> processed = new HashMap<>();
        Instant athosModified = null;
        String athosName = null;
        for (AgentApi.Event event : events) {
            if ("DELETED".equalsIgnoreCase(event.kind())) {
                continue;
            }
            String name = event.fileName();
            switch (event.folderKey().toUpperCase(Locale.ROOT)) {
                case StoreAgentService.TRACEART -> traceart.add(name);
                case StoreAgentService.CTRLPRIX -> {
                    Matcher match = CTRLPRIX.matcher(name);
                    if (match.matches()) {
                        processed.merge(match.group(1).toLowerCase(Locale.ROOT), Integer.parseInt(match.group(2)),
                                Math::max);
                    }
                }
                case StoreAgentService.ATHOS -> {
                    if (event.modified() != null && (athosModified == null || event.modified().isAfter(athosModified))) {
                        athosModified = event.modified();
                        athosName = name;
                    }
                }
                default -> {
                    // Nieznany rodzaj (nowszy agent): pomijamy.
                }
            }
        }
        List<AgentApi.FileRequest> requests = new ArrayList<>();
        for (int from = 0; from < traceart.size(); from += PENDING_CHUNK) {
            priceFiles.pending(storeId, traceart.subList(from, Math.min(traceart.size(), from + PENDING_CHUNK)))
                    .fileNames().forEach(name -> requests.add(
                            new AgentApi.FileRequest(StoreAgentService.TRACEART, name)));
        }
        if (!processed.isEmpty()) {
            seenPriceControl(storeId, processed);
        }
        if (athosName != null) {
            MeaAthosStatus status = athos.status(storeId);
            if (status.fileModified() == null || athosModified.isAfter(status.fileModified())) {
                requests.add(new AgentApi.FileRequest(StoreAgentService.ATHOS, athosName));
            }
        }
        return new AgentApi.EventsResponse(requests);
    }

    /** Pliki TRACEART od agenta (ta sama logika co z przeglądarki), także spakowane gzipem. */
    public PriceFileUploadResponse priceFiles(StoreAgentService.AgentContext agent, List<MultipartFile> files) {
        List<MultipartFile> unpacked = files.stream()
                .map(file -> CompressedUploads.unpacked(file, MAX_PRICE_FILE_BYTES)).toList();
        PriceFileUploadResponse result = priceFiles.upload(agent.storeId(), unpacked, null);
        if (!result.imported().isEmpty()) {
            live.publish(agent.storeId(), "price-files", Map.of("imported", result.imported().size(),
                    "at", Instant.now()));
        }
        return result;
    }

    /**
     * Dziennik ATHOS od agenta, także spakowany gzipem. {@code partial}: sama końcówka pliku od poprzedniej wysyłki
     * (z zakładką), żeby na wolnym łączu nie wysyłać całego rosnącego dziennika po każdej zmianie.
     */
    public MeaAthosStatus athosHistory(StoreAgentService.AgentContext agent, MultipartFile file, Long modified,
            boolean partial) {
        MultipartFile unpacked = CompressedUploads.unpacked(file, MAX_ATHOS_BYTES);
        byte[] bytes;
        try {
            bytes = unpacked.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać dziennika ATHOS");
        }
        MeaAthosStatus status = athos.importHistory(agent.storeId(),
                unpacked.getOriginalFilename() == null ? "Histo.Log" : unpacked.getOriginalFilename(),
                modified == null ? null : Instant.ofEpochMilli(modified), bytes, null, partial);
        live.publish(agent.storeId(), "athos", Map.of("added", status.addedCount(), "at", Instant.now()));
        return status;
    }

    /** Jak przeglądarka: plik z historii łączy się z nazwą w folderze bez końcówki, bez wielkości liter. */
    private void seenPriceControl(UUID storeId, Map<String, Integer> processed) {
        PriceControlFiles before = priceControl.history(storeId);
        List<PriceControlSeenRequest.Seen> seen = new ArrayList<>();
        for (PriceControlFileItem file : concat(before)) {
            Integer count = processed.get(baseName(file.fileName()));
            if (count != null && count > 0 && (file.loadedAt() == null
                    || (file.processedCount() != null && count > file.processedCount()))) {
                seen.add(new PriceControlSeenRequest.Seen(file.id(), count));
            }
        }
        if (seen.isEmpty()) {
            return;
        }
        Set<UUID> wasPending = new HashSet<>(before.pending().stream().map(PriceControlFileItem::id).toList());
        PriceControlFiles after = priceControl.seen(storeId, new PriceControlSeenRequest(seen), null);
        Set<UUID> stillPending = new HashSet<>(after.pending().stream().map(PriceControlFileItem::id).toList());
        for (PriceControlFileItem file : after.loaded()) {
            if (wasPending.contains(file.id()) && !stillPending.contains(file.id())) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("action", "loaded");
                fields.put("id", file.id());
                fields.put("fileName", file.fileName());
                if (file.supplierName() != null) {
                    fields.put("supplier", file.supplierName());
                }
                fields.put("at", Instant.now());
                live.publish(storeId, "price-control", fields);
            }
        }
        if (seen.stream().anyMatch(item -> !wasPending.contains(item.id()))) {
            live.publish(storeId, "price-control", Map.of("action", "processed", "at", Instant.now()));
        }
    }

    private static List<PriceControlFileItem> concat(PriceControlFiles files) {
        List<PriceControlFileItem> all = new ArrayList<>(files.pending());
        all.addAll(files.loaded());
        return all;
    }

    private static String baseName(String fileName) {
        return fileName.replaceFirst("(?i)\\.traite\\d+$", "").toLowerCase(Locale.ROOT);
    }
}
