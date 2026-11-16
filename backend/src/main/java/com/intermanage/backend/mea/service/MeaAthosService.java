package com.intermanage.backend.mea.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mea.component.AthosHistoryParser;
import com.intermanage.backend.mea.dto.MeaAthosStatus;
import com.intermanage.backend.mea.entity.MeaAthosImport;
import com.intermanage.backend.mea.repository.MeaAthosImportRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Statusy wysyłki MEA z ATHOS: przeglądarka czyta dziennik ATHOS (Histo.Log) z folderu wybranego raz i wysyła go tu,
 * a wysyłki trafiają do bazy (bez powtórzeń). Na nich opierają się zakładki „Do wysłania” / „Wysłane” listy MEA.
 */
@Service
public class MeaAthosService {

    /** Najwyżej tyle bajtów dziennika czytamy (dziennik rośnie latami; limit żądania i tak jest 50 MB). */
    private static final int MAX_FILE_BYTES = 50 * 1024 * 1024;

    private final MeaAthosImportRepository imports;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final AthosHistoryParser parser;

    public MeaAthosService(MeaAthosImportRepository imports, StoreRepository stores, AppUserRepository users,
            AthosHistoryParser parser) {
        this.imports = imports;
        this.stores = stores;
        this.users = users;
        this.parser = parser;
    }

    /** Odczyt dziennika: dopisuje nowe wysyłki i zapisuje sam odczyt. Plik bez żadnej wysyłki MEA to 400. */
    @Transactional
    public MeaAthosStatus importHistory(UUID storeId, String fileName, Instant fileModified, byte[] file,
            UUID userId) {
        if (file.length > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Dziennik ATHOS jest za duży (ponad 50 MB)");
        }
        List<AthosHistoryParser.Send> parsed = parser.parse(file);
        if (parsed.isEmpty()) {
            throw ApiException.badRequest("W pliku " + fileName + " nie ma wysyłek zamówień MEA. Sprawdź, czy to "
                    + "dziennik ATHOS (Histo.Log z folderu appres\\athos2000)");
        }
        // Ten sam wpis może się powtórzyć w pliku; w bazie i tak jest unikalny.
        Map<String, AthosHistoryParser.Send> unique = new LinkedHashMap<>();
        for (AthosHistoryParser.Send send : parsed) {
            unique.putIfAbsent(send.number() + "|" + send.sentAt(), send);
        }
        List<AthosHistoryParser.Send> sends = List.copyOf(unique.values());
        int added = imports.insertSends(storeId,
                sends.stream().map(AthosHistoryParser.Send::number).toArray(String[]::new),
                sends.stream().map(AthosHistoryParser.Send::sentAt).toArray(LocalDateTime[]::new),
                sends.stream().map(AthosHistoryParser.Send::positions).toArray(Integer[]::new),
                sends.stream().map(AthosHistoryParser.Send::colis).toArray(Integer[]::new));
        LocalDateTime lastSentAt = sends.stream().map(AthosHistoryParser.Send::sentAt)
                .max(Comparator.naturalOrder()).orElse(null);
        imports.save(new MeaAthosImport(stores.getReferenceById(storeId), fileName, fileModified, sends.size(), added,
                lastSentAt, users.getReferenceById(userId)));
        return status(storeId);
    }

    @Transactional(readOnly = true)
    public MeaAthosStatus status(UUID storeId) {
        MeaAthosImport last = imports.findFirstByStoreIdOrderByImportedAtDesc(storeId).orElse(null);
        if (last == null) {
            return new MeaAthosStatus(false, null, null, null, null, 0, 0, 0, null);
        }
        return new MeaAthosStatus(true, last.getImportedAt(),
                last.getImportedBy() == null ? null : last.getImportedBy().getDisplayName(), last.getFileName(),
                last.getFileModified(), last.getFoundCount(), last.getAddedCount(), imports.countSends(storeId),
                imports.findLatestSends(storeId).stream().map(MeaAthosImportRepository.LatestSend::getSentAt)
                        .max(Comparator.naturalOrder()).orElse(null));
    }

    /**
     * Najnowsza wysyłka każdego MEA sklepu; {@code null}, gdy sklep nie odczytał jeszcze dziennika (wtedy zakładki
     * liczą się po staremu, z flagi zamówienia z portalu).
     */
    @Transactional(readOnly = true)
    public Map<String, MeaAthosImportRepository.LatestSend> latestSends(UUID storeId) {
        if (!imports.existsByStoreId(storeId)) {
            return null;
        }
        return imports.findLatestSends(storeId).stream()
                .collect(Collectors.toMap(MeaAthosImportRepository.LatestSend::getNumber, Function.identity()));
    }

    /** Wysyłki jednego MEA, od najnowszej. */
    @Transactional(readOnly = true)
    public List<MeaAthosImportRepository.LatestSend> sends(UUID storeId, String number) {
        return imports.findSends(storeId, number);
    }
}
