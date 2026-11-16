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
import com.intermanage.backend.mea.repository.MeaOrderRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Zamówienie MEA do ATHOS jak „Wyślij do ATHOS” w eMEA: serwer składa plik V… (wszystkie pozycje formularza,
 * kartony z żądania) i oddaje go spakowanego jako R…, a przeglądarka zapisuje go w folderze comrnis serwera sklepu
 * i dopiero wtedy potwierdza zapis ({@link #saved}). Po terminie rezerwacji zamówienia nie da się już złożyć.
 */
@Service
public class MeaOrderService {

    /** Gotowy plik do zapisu w folderze: wpis zamówienia, nazwa ZIP-a i jego treść. */
    public record Prepared(UUID id, String fileName, byte[] zip) {
    }

    private final MeaOrderRepository orders;
    private final MeaDocumentService documents;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public MeaOrderService(MeaOrderRepository orders, MeaDocumentService documents, StoreRepository stores,
            AppUserRepository users) {
        this.orders = orders;
        this.documents = documents;
        this.stores = stores;
        this.users = users;
    }

    /** Składa plik zamówienia i zapisuje wpis jeszcze bez potwierdzenia zapisu na dysku. */
    public Prepared prepare(UUID storeId, String number, MeaOrderRequest request, UUID userId) {
        MeaDocument document = documents.load(storeId, number);
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        if (document.getReservationTo() != null && document.getReservationTo().isBefore(today)) {
            throw ApiException.conflict("Rezerwacja MEA " + number + " zakończyła się "
                    + document.getReservationTo() + ". Zamówienia nie da się już wysłać");
        }
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
        String textName = AthosOrderFile.textName(number, storeNumber);
        String zipName = AthosOrderFile.zipName(number, storeNumber);
        MeaOrder order = orders.save(new MeaOrder(store, number, zipName, content, positions, colis,
                users.getReferenceById(userId)));
        return new Prepared(order.getId(), zipName, AthosOrderFile.zip(textName, content));
    }

    /** Plik leży już w folderze comrnis: od teraz MEA jest wysłane. */
    @Transactional
    public void saved(UUID storeId, UUID orderId) {
        orders.findByIdAndStoreId(orderId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zamówienia MEA"))
                .saved();
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
