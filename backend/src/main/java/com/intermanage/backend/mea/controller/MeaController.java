package com.intermanage.backend.mea.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.intermanage.backend.mea.dto.MeaArticleSales;
import com.intermanage.backend.mea.dto.MeaAthosStatus;
import com.intermanage.backend.mea.dto.MeaDocumentResponse;
import com.intermanage.backend.mea.dto.MeaLeafletSummary;
import com.intermanage.backend.mea.dto.MeaListResponse;
import com.intermanage.backend.mea.dto.MeaOrderRequest;
import com.intermanage.backend.mea.dto.MeaQueueItem;
import com.intermanage.backend.mea.dto.MeaSettingsRequest;
import com.intermanage.backend.mea.dto.MeaSettingsResponse;
import com.intermanage.backend.mea.entity.MeaOrder;
import com.intermanage.backend.mea.service.MeaAthosService;
import com.intermanage.backend.mea.service.MeaDocumentService;
import com.intermanage.backend.mea.service.MeaLeafletService;
import com.intermanage.backend.mea.service.MeaOrderService;
import com.intermanage.backend.mea.service.MeaSalesService;
import com.intermanage.backend.mea.service.MeaService;

/**
 * Moduł MEA: lista MEA z portalu Grupy Muszkieterów dla sklepu i zapis kluczy API sklepu. Uprawnienie jak przy
 * dostawie z bazy - kto wprowadza faktury i dokumenty z bazy, widzi też MEA; zapis kluczy wymaga EDIT.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/mea")
public class MeaController {

    private final MeaService service;
    private final MeaDocumentService documents;
    private final MeaAthosService athos;
    private final MeaOrderService orders;
    private final MeaLeafletService leaflets;
    private final MeaSalesService sales;

    public MeaController(MeaService service, MeaDocumentService documents, MeaAthosService athos,
            MeaOrderService orders, MeaLeafletService leaflets, MeaSalesService sales) {
        this.leaflets = leaflets;
        this.sales = sales;
        this.service = service;
        this.documents = documents;
        this.athos = athos;
        this.orders = orders;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaListResponse list(@PathVariable UUID storeId) {
        try {
            MeaListResponse list = service.list(storeId);
            // Nowe MEA trafiają od razu do bazy, a formularze pobierze zadanie w tle albo otwarcie dokumentu.
            documents.register(list.items());
            return list;
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @GetMapping("/documents/{number}")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaDocumentResponse document(@PathVariable UUID storeId, @PathVariable String number) {
        try {
            return documents.document(storeId, number);
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    /**
     * Sprzedaż dzienna artykułów (ITM8 z kartoteki) w ostatnich {@code weeks} tygodniach czwartek-środa z promocjami:
     * tygodnie pod pozycjami dokumentu MEA i dni w panelu artykułu.
     */
    @GetMapping("/article-sales")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaArticleSales articleSales(@PathVariable UUID storeId,
            @RequestParam(name = "itm8", required = false) List<String> itm8s,
            @RequestParam(defaultValue = "" + MeaSalesService.DEFAULT_WEEKS) int weeks) {
        return sales.sales(storeId, itm8s, weeks);
    }

    /** Wgrane opisy gazetek z centrali (wspólne dla sklepów), od najnowszej gazetki. */
    @GetMapping("/leaflets")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<MeaLeafletSummary> leaflets(@PathVariable UUID storeId) {
        return leaflets.list();
    }

    /**
     * Sprawdza portal od razu (w tle robi to zadanie MEA co kwadrans) sesją tego sklepu i pobiera nowe opisy gazetek.
     */
    @PostMapping("/leaflets/sync")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaLeafletSummary.Sync syncLeaflets(@PathVariable UUID storeId) {
        try {
            int downloaded = leaflets.syncFromPortal(storeId);
            return new MeaLeafletSummary.Sync(downloaded, leaflets.list());
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    /**
     * Opis gazetki wgrany ręcznie (zapas, gdy portal nie działa); ponowne wgranie tej samej gazetki zastępuje
     * poprzednie.
     */
    @PostMapping(value = "/leaflets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaLeafletSummary uploadLeaflet(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return leaflets.upload(file, principal.getId());
    }

    /** Stan odczytów dziennika ATHOS (Histo.Log) w sklepie. */
    @GetMapping("/athos")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaAthosStatus athosStatus(@PathVariable UUID storeId) {
        return athos.status(storeId);
    }

    /**
     * Dziennik ATHOS z folderu wybranego w przeglądarce (czyta go każdy, kto pracuje na MEA; przeglądarka wysyła plik
     * tylko po jego zmianie). {@code modified}: data zmiany pliku w milisekundach.
     */
    @PostMapping(value = "/athos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaAthosStatus athosHistory(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @RequestParam(value = "modified", required = false) Long modified,
            @AuthenticationPrincipal AppUserPrincipal principal) throws IOException {
        return athos.importHistory(storeId,
                file.getOriginalFilename() == null ? "Histo.Log" : file.getOriginalFilename(),
                modified == null ? null : Instant.ofEpochMilli(modified), file.getBytes(), principal.getId());
    }

    /**
     * Zamówienie do ATHOS (jak „Wyślij do ATHOS” w eMEA): ZIP R… z plikiem V… do zapisania przez przeglądarkę w folderze
     * comrnis serwera sklepu; {@code X-Mea-Order-Id} to wpis, który po zapisie potwierdza {@code .../saved}.
     */
    @PostMapping("/documents/{number}/athos-order")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ResponseEntity<byte[]> athosOrder(@PathVariable UUID storeId, @PathVariable String number,
            @RequestBody MeaOrderRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        MeaOrderService.Prepared prepared;
        try {
            prepared = orders.prepare(storeId, number, request, principal.getId());
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(prepared.fileName()).build().toString())
                .header("X-Mea-Order-Id", prepared.id().toString())
                .body(prepared.zip());
    }

    /**
     * Zamówienie do kolejki sklepu (plik zostaje w bazie): w folderze comrnis zapisze go „Wyślij wszystkie” na komputerze
     * z dostępem do ATHOS. Plik tego MEA, który jeszcze czekał, zastępuje nowy.
     */
    @PostMapping("/documents/{number}/athos-queue")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaQueueItem queueAthosOrder(@PathVariable UUID storeId, @PathVariable String number,
            @RequestBody MeaOrderRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        try {
            MeaOrder order = orders.queue(storeId, number, request, principal.getId());
            return orders.queue(storeId).stream()
                    .filter(item -> item.order().getId().equals(order.getId()))
                    .findFirst().map(MeaController::queueItem)
                    .orElseThrow(() -> ApiException.notFound("Nie znaleziono zamówienia MEA"));
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    /** Pliki czekające w kolejce sklepu, od najstarszego. */
    @GetMapping("/athos-queue")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<MeaQueueItem> athosQueue(@PathVariable UUID storeId) {
        return orders.queue(storeId).stream().map(MeaController::queueItem).toList();
    }

    /** ZIP R… z kolejki do zapisu w folderze comrnis; po zapisie potwierdza go {@code .../athos-orders/{id}/saved}. */
    @GetMapping("/athos-queue/{orderId}/file")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ResponseEntity<byte[]> athosQueueFile(@PathVariable UUID storeId, @PathVariable UUID orderId) {
        MeaOrderService.Prepared prepared = orders.queuedFile(storeId, orderId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(prepared.fileName()).build().toString())
                .header("X-Mea-Order-Id", prepared.id().toString())
                .body(prepared.zip());
    }

    /** Zdejmuje plik z kolejki (znacznik, toast „Przywróć”). */
    @DeleteMapping("/athos-queue/{orderId}")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromAthosQueue(@PathVariable UUID storeId, @PathVariable UUID orderId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        orders.removeFromQueue(storeId, orderId, principal.getId());
    }

    @PostMapping("/athos-queue/{orderId}/restore")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreToAthosQueue(@PathVariable UUID storeId, @PathVariable UUID orderId) {
        orders.restoreToQueue(storeId, orderId);
    }

    private static MeaQueueItem queueItem(MeaOrderService.Queued queued) {
        MeaOrder order = queued.order();
        return new MeaQueueItem(order.getId(), order.getNumber(), queued.title(), order.getFileName(),
                order.getPositions(), order.getColis(),
                order.getCreatedBy() == null ? null : order.getCreatedBy().getDisplayName(), order.getQueuedAt(),
                queued.reservationTo());
    }

    /** Plik zamówienia leży już w folderze comrnis: MEA jest wysłane. */
    @PostMapping("/athos-orders/{orderId}/saved")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void athosOrderSaved(@PathVariable UUID storeId, @PathVariable UUID orderId) {
        orders.saved(storeId, orderId);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaSettingsResponse settings(@PathVariable UUID storeId) {
        return service.settings(storeId);
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('DATA_ENTRY:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaSettingsResponse save(@PathVariable UUID storeId, @RequestBody MeaSettingsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, request, principal.getId());
    }
}
