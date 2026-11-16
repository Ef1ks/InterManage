package com.intermanage.backend.mea.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.mea.component.AthosOrderFile;
import com.intermanage.backend.mea.dto.ArticleMeaResponse;
import com.intermanage.backend.mea.dto.ArticleMeaResponse.Dispatch;
import com.intermanage.backend.mea.dto.ArticleMeaResponse.Status;
import com.intermanage.backend.mea.entity.MeaDocument;
import com.intermanage.backend.mea.entity.MeaOrder;
import com.intermanage.backend.mea.repository.MeaAthosImportRepository;
import com.intermanage.backend.mea.repository.MeaDocumentRepository;
import com.intermanage.backend.mea.repository.MeaOrderRepository;

/**
 * Gazetki MEA artykułu, które jeszcze trwają albo dopiero będą (karta artykułu): okres, rezerwacja, rozrzuty i co
 * sklep zamówił. Ilości artykułu znamy tylko z zamówień zapisanych w aplikacji (treść pliku do ATHOS); dziennik ATHOS
 * mówi tylko, że MEA wysłano, bez rozbicia na artykuły.
 */
@Service
public class MeaArticleService {

    private final MeaDocumentRepository documents;
    private final MeaOrderRepository orders;
    private final MeaAthosImportRepository athos;

    public MeaArticleService(MeaDocumentRepository documents, MeaOrderRepository orders,
            MeaAthosImportRepository athos) {
        this.documents = documents;
        this.orders = orders;
        this.athos = athos;
    }

    @Transactional(readOnly = true)
    public List<ArticleMeaResponse> upcoming(UUID storeId, String itm8) {
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        // Jedna pozycja na MEA: artykuł bywa w formularzu pod kilkoma kodami, liczy się pierwsza.
        Map<String, MeaDocumentRepository.ArticleLine> lines = new LinkedHashMap<>();
        documents.findArticleLines(storeId, itm8.strip(), today)
                .forEach(line -> lines.putIfAbsent(line.getNumber(), line));
        if (lines.isEmpty()) {
            return List.of();
        }
        Map<String, MeaDocument> byNumber = documents.findByNumberIn(lines.keySet()).stream()
                .collect(Collectors.toMap(MeaDocument::getNumber, Function.identity()));
        boolean athosRead = athos.existsByStoreId(storeId);
        List<ArticleMeaResponse> result = new ArrayList<>();
        lines.forEach((number, line) -> {
            MeaDocument document = byNumber.get(number);
            if (document != null) {
                result.add(describe(storeId, document, line, athosRead, today));
            }
        });
        return result;
    }

    private ArticleMeaResponse describe(UUID storeId, MeaDocument document, MeaDocumentRepository.ArticleLine line,
            boolean athosRead, LocalDate today) {
        String number = document.getNumber();
        Optional<MeaOrder> saved = orders.findByStoreIdAndNumberAndSavedAtIsNotNullOrderBySavedAtDesc(storeId, number)
                .stream().findFirst();
        Optional<MeaOrder> queued = orders.findQueued(storeId, number).stream().findFirst()
                .filter(order -> saved.isEmpty() || order.getCreatedAt().isAfter(saved.get().getSavedAt()));
        Optional<MeaAthosImportRepository.LatestSend> sent = athos.findSends(storeId, number).stream().findFirst();

        MeaOrder order = queued.orElse(saved.orElse(null));
        int[] colis = order == null ? null : colisOf(order, line.getMeaItm8());
        Status status;
        if (queued.isPresent()) {
            status = Status.QUEUED;
        } else if (order != null) {
            status = colis != null && hasAny(colis) ? Status.ORDERED : Status.NOT_ORDERED;
        } else if (sent.isPresent()) {
            status = Status.SENT;
        } else if (document.getReservationTo() == null || !document.getReservationTo().isBefore(today)) {
            status = Status.OPEN;
        } else {
            status = athosRead ? Status.CLOSED : Status.UNKNOWN;
        }

        List<LocalDate> dates = document.getDispatches();
        List<Dispatch> dispatches = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            Integer amount = colis == null ? null : colis[index];
            dispatches.add(new Dispatch(index + 1, dates.get(index), amount));
        }
        return new ArticleMeaResponse(number, document.getTitle(), document.getPromoFrom(), document.getPromoTo(),
                document.getReservationTo(), line.getMeaItm8(), line.getUnitsPerPack(), status,
                order == null ? null : order.getSavedAt() != null ? order.getSavedAt() : order.getCreatedAt(),
                order == null || order.getCreatedBy() == null ? null : order.getCreatedBy().getDisplayName(),
                sent.map(MeaAthosImportRepository.LatestSend::getSentAt).orElse(null),
                sent.map(MeaAthosImportRepository.LatestSend::getColis).orElse(null),
                dispatches);
    }

    /** Kartony pozycji z pliku zamówienia; pozycji nie ma w pliku = zero kartonów. */
    private static int[] colisOf(MeaOrder order, String meaItm8) {
        String key = meaItm8 == null ? "" : meaItm8.replaceFirst("^0+", "");
        return AthosOrderFile.parse(order.getContent()).stream()
                .filter(record -> record.itm8().equals(key))
                .map(AthosOrderFile.Record::colis)
                .findFirst()
                .orElse(new int[AthosOrderFile.DISPATCHES]);
    }

    private static boolean hasAny(int[] colis) {
        for (int value : colis) {
            if (value > 0) {
                return true;
            }
        }
        return false;
    }
}
