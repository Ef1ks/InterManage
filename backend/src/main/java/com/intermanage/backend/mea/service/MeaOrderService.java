package com.intermanage.backend.mea.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mea.component.AthosOrderFile;
import com.intermanage.backend.mea.dto.MeaOrderRequest;
import com.intermanage.backend.mea.entity.MeaDocument;
import com.intermanage.backend.mea.entity.MeaDocumentLine;
import com.intermanage.backend.mea.entity.MeaOrder;
import com.intermanage.backend.mea.repository.MeaDocumentRepository;
import com.intermanage.backend.mea.repository.MeaOrderRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Zamówienie MEA do ATHOS jak „Wyślij do ATHOS” w eMEA: serwer składa plik V… (wszystkie pozycje formularza,
 * kartony z żądania) i oddaje go spakowanego jako R…, a przeglądarka zapisuje go w folderze comrnis serwera sklepu
 * i dopiero wtedy potwierdza zapis ({@link #saved}). Albo plik czeka w kolejce sklepu ({@link #queue}), a w folderze
 * zapisuje go „Wyślij wszystkie” na komputerze z dostępem do ATHOS. Po terminie rezerwacji zamówienia nie da się już
 * złożyć ani wysłać z kolejki.
 */
@Service
public class MeaOrderService {

    /** Gotowy plik do zapisu w folderze: wpis zamówienia, nazwa ZIP-a i jego treść. */
    public record Prepared(UUID id, String fileName, byte[] zip) {
    }

    private final MeaOrderRepository orders;
    private final MeaDocumentService documents;
    private final MeaDocumentRepository meaDocuments;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public MeaOrderService(MeaOrderRepository orders, MeaDocumentService documents,
            MeaDocumentRepository meaDocuments, StoreRepository stores, AppUserRepository users) {
        this.orders = orders;
        this.documents = documents;
        this.meaDocuments = meaDocuments;
        this.stores = stores;
        this.users = users;
    }

    /** Plik czekający w kolejce sklepu z tytułem MEA i końcem rezerwacji (po nim ATHOS go już nie przyjmie). */
    public record Queued(MeaOrder order, String title, LocalDate reservationTo) {
    }

    /** Składa plik zamówienia i zapisuje wpis jeszcze bez potwierdzenia zapisu na dysku. */
    public Prepared prepare(UUID storeId, String number, MeaOrderRequest request, UUID userId) {
        MeaOrder order = orders.save(build(storeId, number, request, userId, false));
        return new Prepared(order.getId(), order.getFileName(), zip(order));
    }

    /**
     * Zamówienie do kolejki sklepu: plik zostaje w bazie, a w folderze comrnis zapisze go „Wyślij wszystkie” na
     * komputerze z dostępem do ATHOS. Plik tego MEA, który jeszcze czeka, zastępuje nowy.
     */
    @Transactional
    public MeaOrder queue(UUID storeId, String number, MeaOrderRequest request, UUID userId) {
        List<MeaOrder> waiting = orders.findQueued(storeId, number);
        MeaOrder order = orders.save(build(storeId, number, request, userId, true));
        waiting.forEach(other -> other.replaceWith(order));
        return order;
    }

    /** Kolejka sklepu, od najstarszego pliku. */
    @Transactional(readOnly = true)
    public List<Queued> queue(UUID storeId) {
        List<MeaOrder> queued = orders.findQueue(storeId);
        Map<String, MeaDocument> byNumber = meaDocuments.findByNumberIn(
                queued.stream().map(MeaOrder::getNumber).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(MeaDocument::getNumber, Function.identity(), (a, b) -> a));
        return queued.stream().map(order -> {
            MeaDocument document = byNumber.get(order.getNumber());
            return new Queued(order, document == null ? null : document.getTitle(),
                    document == null ? null : document.getReservationTo());
        }).toList();
    }

    /** Plik z kolejki do zapisu w folderze; po terminie rezerwacji 409, bo ATHOS by go nie przyjął. */
    @Transactional(readOnly = true)
    public Prepared queuedFile(UUID storeId, UUID orderId) {
        MeaOrder order = find(storeId, orderId);
        if (!order.isPending()) {
            throw ApiException.conflict("Zamówienia MEA " + order.getNumber() + " nie ma już w kolejce");
        }
        meaDocuments.findByNumber(order.getNumber()).ifPresent(MeaOrderService::checkReservation);
        return new Prepared(order.getId(), order.getFileName(), zip(order));
    }

    /** Zdejmuje plik z kolejki (znacznik, do przywrócenia). */
    @Transactional
    public void removeFromQueue(UUID storeId, UUID orderId, UUID userId) {
        MeaOrder order = find(storeId, orderId);
        if (!order.isPending()) {
            throw ApiException.conflict("Zamówienia MEA " + order.getNumber() + " nie ma już w kolejce");
        }
        order.remove(users.getReferenceById(userId));
    }

    /** Przywraca plik zdjęty z kolejki; gdy w międzyczasie dodano nowszy plik tego MEA, przywrócić się nie da. */
    @Transactional
    public void restoreToQueue(UUID storeId, UUID orderId) {
        MeaOrder order = find(storeId, orderId);
        if (!order.isRemoved() || order.isReplaced() || order.getSavedAt() != null) {
            throw ApiException.conflict("Tego zamówienia nie da się przywrócić do kolejki");
        }
        if (!orders.findQueued(storeId, order.getNumber()).isEmpty()) {
            throw ApiException.conflict("W kolejce jest już nowsze zamówienie MEA " + order.getNumber());
        }
        order.restore();
    }

    private MeaOrder find(UUID storeId, UUID orderId) {
        return orders.findByIdAndStoreId(orderId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zamówienia MEA"));
    }

    private static void checkReservation(MeaDocument document) {
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        if (document.getReservationTo() != null && document.getReservationTo().isBefore(today)) {
            throw ApiException.conflict("Rezerwacja MEA " + document.getNumber() + " zakończyła się "
                    + document.getReservationTo() + ". Zamówienia nie da się już wysłać");
        }
    }

    /** ZIP R… z plikiem V… (ta sama nazwa po literze). */
    private static byte[] zip(MeaOrder order) {
        return AthosOrderFile.zip("V" + order.getFileName().substring(1), order.getContent());
    }

    private MeaOrder build(UUID storeId, String number, MeaOrderRequest request, UUID userId, boolean queued) {
        MeaDocument document = documents.load(storeId, number);
        checkReservation(document);
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        int dispatches = document.getDispatches().size();
        Map<String, List<Integer>> requested = new HashMap<>();
        for (MeaOrderRequest.Line line : request.lines() == null ? List.<MeaOrderRequest.Line>of() : request.lines()) {
            if (line.itm8() != null) {
                requested.put(line.itm8().strip(), line.colis() == null ? List.of() : line.colis());
            }
        }
        Map<String, MeaDocumentLine> known = document.getLines().stream()
                .collect(Collectors.toMap(MeaDocumentLine::getMeaItm8, Function.identity(), (a, b) -> a));
        for (String itm8 : requested.keySet()) {
            if (!known.containsKey(itm8)) {
                throw ApiException.badRequest("Pozycji " + itm8 + " nie ma w MEA " + number);
            }
        }
        List<AthosOrderFile.Line> lines = new ArrayList<>();
        int positions = 0;
        int colis = 0;
        for (MeaDocumentLine line : document.getLines()) {
            List<Integer> values = requested.getOrDefault(line.getMeaItm8(), List.of());
            int[] perDispatch = new int[AthosOrderFile.DISPATCHES];
            for (int i = 0; i < values.size(); i++) {
                int value = values.get(i) == null ? 0 : values.get(i);
                if (value == 0) {
                    continue;
                }
                if (i >= Math.min(dispatches, AthosOrderFile.DISPATCHES)) {
                    throw ApiException.badRequest("Rozrzut R" + (i + 1) + " w MEA " + number + " nie ma daty");
                }
                perDispatch[i] = value;
            }
            int lineColis = java.util.Arrays.stream(perDispatch).sum();
            if (lineColis > 0) {
                positions++;
                colis += lineColis;
            }
            lines.add(new AthosOrderFile.Line(line.getMeaItm8(), perDispatch));
        }
        String content;
        String storeNumber;
        try {
            storeNumber = AthosOrderFile.storeNumber(store.getNumber());
            content = AthosOrderFile.content(number, lines);
        } catch (AthosOrderFile.InvalidOrder exception) {
            throw ApiException.badRequest(exception.getMessage());
        }
        String zipName = AthosOrderFile.zipName(number, storeNumber);
        return queued
                ? MeaOrder.queued(store, number, zipName, content, positions, colis, users.getReferenceById(userId))
                : new MeaOrder(store, number, zipName, content, positions, colis, users.getReferenceById(userId));
    }

    /**
     * Plik zamówienia leży już w folderze comrnis: od teraz MEA jest wysłane. Zapis od razu zastępuje starszy plik
     * tego MEA, który czekał w kolejce, bo w folderze jest już nowszy (a wysłany z kolejki nadpisałby go).
     */
    @Transactional
    public void saved(UUID storeId, UUID orderId) {
        MeaOrder order = find(storeId, orderId);
        order.saved();
        orders.findQueued(storeId, order.getNumber()).stream()
                .filter(other -> other.getCreatedAt().isBefore(order.getCreatedAt()))
                .forEach(other -> other.replaceWith(order));
    }

    /** Zapisane zamówienia jednego MEA, od najnowszego. */
    @Transactional(readOnly = true)
    public List<MeaOrder> saved(UUID storeId, String number) {
        List<MeaOrder> result = orders.findByStoreIdAndNumberAndSavedAtIsNotNullOrderBySavedAtDesc(storeId, number);
        result.forEach(order -> {
            if (order.getCreatedBy() != null) {
                order.getCreatedBy().getDisplayName();
            }
        });
        return result;
    }

    /** Najnowsze zapisane zamówienie każdego MEA sklepu. */
    @Transactional(readOnly = true)
    public Map<String, MeaOrder> latestSaved(UUID storeId) {
        return orders.findLatestSaved(storeId).stream()
                .collect(Collectors.toMap(MeaOrder::getNumber, Function.identity(), (a, b) -> a));
    }
}
