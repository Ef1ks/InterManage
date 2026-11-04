package com.intermanage.backend.dataentry.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.LabelFile;
import com.intermanage.backend.dataentry.dto.LabelRequest;
import com.intermanage.backend.dataentry.dto.ProtocolRequest;
import com.intermanage.backend.store.entity.ProtocolKind;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.store.service.StoreProtoSettingsService;

/**
 * Protokół z tabeli wprowadzania danych (likwidacje, potrzeby sklepu, darowizna, reklama, reprezentacja, MM) jako plik
 * PROTO1-PROTO6 dla programu sklepu. Ilości przechodzą ze znakiem, bo tabela może je odwrócić (+ / -).
 */
@Service
public class DataEntryProtocolService {

    public record ProtocolFile(String fileName, byte[] content) {
    }

    private final StoreRepository stores;
    private final StoreProtoSettingsService settings;

    public DataEntryProtocolService(StoreRepository stores, StoreProtoSettingsService settings) {
        this.stores = stores;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ProtocolFile generate(UUID storeId, ProtocolKind kind, ProtocolRequest request, String username) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (settings.find(storeId).isHidden(kind)) {
            throw ApiException.badRequest("Protokół „" + kind.label() + "” jest wyłączony w ustawieniach sklepu");
        }
        List<ProtoFile.Line> lines = request.lines().stream()
                .map(line -> new ProtoFile.Line(line.ean(), line.quantity()))
                .toList();
        byte[] content = ProtoFile.content(lines, true);
        if (content.length == 0) {
            throw ApiException.badRequest("Brak pozycji z EAN-em i ilością różną od 0");
        }
        return new ProtocolFile(ProtoFile.fileName(kind.number(), store.getNumber(), username), content);
    }

    /** Plik etykiet cenowych ETIQUETTE z artykułów tabeli (zapisuje go przeglądarka w folderze plików PROTO). */
    public ProtocolFile labels(LabelRequest request, String username) {
        byte[] content = LabelFile.content(request.lines().stream()
                .map(line -> new LabelFile.Line(line.ean(), line.count()))
                .toList());
        if (content.length == 0) {
            throw ApiException.badRequest("Brak pozycji z EAN-em do etykiet");
        }
        return new ProtocolFile(LabelFile.fileName(username, request.format()), content);
    }
}
