package com.intermanage.backend.document.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.Proto6;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.IncomingTransfer;
import com.intermanage.backend.document.dto.IncomingTransfers;
import com.intermanage.backend.document.dto.ReceiveTransferRequest;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentTransferReceivedLine;
import com.intermanage.backend.document.entity.DocumentTransferTarget;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.mapper.DocumentMapper;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentTransferReceivedLineRepository;
import com.intermanage.backend.document.repository.DocumentTransferTargetRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Ruch międzymagazynowy (MM) wystawiony dla sklepu zaprzyjaźnionego. Sklep wysyłający wskazuje sklep docelowy
 * (tylko zaakceptowany sklep zaprzyjaźniony), a sklep docelowy widzi dokument z liniami do wprowadzenia u siebie
 * i oznacza go jako wprowadzony. Nic nie jest kasowane: cofnięcie to znacznik, zmiana sklepu cofa poprzedni wpis.
 */
@Service
public class DocumentTransferService {

    /** Ile ostatnio wprowadzonych dokumentów pokazuje sklep docelowy pod czekającymi. */
    static final int RECENTLY_ENTERED = 30;

    private final DocumentRepository documents;
    private final DocumentTransferTargetRepository targets;
    private final StoreRepository stores;
    private final StorePartnerRepository partners;
    private final AppUserRepository users;
    private final DocumentService documentService;
    private final DocumentMapper documentMapper;
    private final DocumentTransferReceivedLineRepository receivedLines;
    private final XlsxWriter xlsxWriter;

    public DocumentTransferService(DocumentRepository documents, DocumentTransferTargetRepository targets,
            StoreRepository stores, StorePartnerRepository partners, AppUserRepository users,
            DocumentService documentService, DocumentMapper documentMapper,
            DocumentTransferReceivedLineRepository receivedLines, XlsxWriter xlsxWriter) {
        this.documents = documents;
        this.targets = targets;
        this.stores = stores;
        this.partners = partners;
        this.users = users;
        this.documentService = documentService;
        this.documentMapper = documentMapper;
        this.receivedLines = receivedLines;
        this.xlsxWriter = xlsxWriter;
    }

    /** Wskazuje sklep docelowy albo go zmienia; zmiana zeruje "wprowadzony", bo to inny sklep. */
    @Transactional
    public DocumentResponse setTarget(UUID storeId, String typeCode, UUID documentId, UUID targetStoreId,
            UUID userId) {
        Document document = transfer(storeId, typeCode, documentId);
        Store target = stores.findById(targetStoreId).filter(Store::isActive)
                .orElseThrow(() -> ApiException.badRequest("Wybrany sklep nie istnieje albo jest wyłączony"));
        if (!partners.arePartners(storeId, targetStoreId)) {
            throw ApiException.badRequest(
                    "Dokument można wystawić tylko dla sklepu zaprzyjaźnionego (Ustawienia sklepu)");
        }
        AppUser user = user(userId);
        DocumentTransferTarget current = targets.findActive(document.getId()).orElse(null);
        if (current == null || !current.getTargetStore().getId().equals(targetStoreId)) {
            if (current != null) {
                current.markDeleted(user);
                targets.flush();
            }
            targets.save(new DocumentTransferTarget(document, target, user));
        }
        return documentService.document(storeId, typeCode, documentId);
    }

    @Transactional
    public DocumentResponse cancel(UUID storeId, String typeCode, UUID documentId, UUID userId) {
        Document document = transfer(storeId, typeCode, documentId);
        targets.findActive(document.getId())
                .orElseThrow(() -> ApiException.notFound("Dokument nie jest wystawiony dla innego sklepu"))
                .markDeleted(user(userId));
        return documentService.document(storeId, typeCode, documentId);
    }

    /** Przywraca ostatnio cofnięty sklep docelowy ("Przywróć" w powiadomieniu), razem z "wprowadzony". */
    @Transactional
    public DocumentResponse restore(UUID storeId, String typeCode, UUID documentId) {
        Document document = transfer(storeId, typeCode, documentId);
        if (targets.findActive(document.getId()).isPresent()) {
            throw ApiException.conflict("Dokument jest już wystawiony dla sklepu");
        }
        targets.findFirstByDocumentIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(document.getId())
                .orElseThrow(() -> ApiException.notFound("Nie ma czego przywrócić"))
                .restore();
        targets.flush();
        return documentService.document(storeId, typeCode, documentId);
    }

    /** Dokumenty innych sklepów wystawione dla sklepu: czekające (wszystkie) i ostatnio wprowadzone. */
    @Transactional(readOnly = true)
    public IncomingTransfers incoming(UUID storeId) {
        return new IncomingTransfers(
                targets.findPending(storeId).stream()
                        .map(target -> documentMapper.toIncoming(target, List.of(), List.of())).toList(),
                targets.findEntered(storeId, Limit.of(RECENTLY_ENTERED)).stream()
                        .map(target -> documentMapper.toIncoming(target, List.of(), List.of())).toList());
    }

    /** Ile dokumentów czeka na wprowadzenie (plakietka w menu). */
    @Transactional(readOnly = true)
    public long pendingCount(UUID storeId) {
        return targets.countByTargetStoreIdAndDeletedAtIsNullAndEnteredAtIsNull(storeId);
    }

    /** Dokument innego sklepu z liniami; sklep docelowy nie ma dostępu do sklepu wysyłającego, tylko do tego wpisu. */
    @Transactional(readOnly = true)
    public IncomingTransfer incomingTransfer(UUID storeId, UUID targetId) {
        DocumentTransferTarget target = incoming(storeId, targetId);
        return documentMapper.toIncoming(target, withLines(target).getLines(), receivedLines.findByTargetId(targetId));
    }

    /**
     * Zapisuje ilości, które naprawdę przyjechały (linia pominięta = ilość z dokumentu, poprawka nadpisuje
     * poprzednią), i oznacza dokument jako wprowadzony. Z tych ilości powstaje plik PROTO6 i Excel.
     */
    @Transactional
    public IncomingTransfer receive(UUID storeId, UUID targetId, List<ReceiveTransferRequest.Item> items,
            UUID userId) {
        DocumentTransferTarget target = incoming(storeId, targetId);
        Set<UUID> lineIds = withLines(target).getLines().stream().map(DocumentLine::getId)
                .collect(Collectors.toSet());
        Map<UUID, DocumentTransferReceivedLine> saved = receivedLines.findByTargetId(targetId).stream()
                .collect(Collectors.toMap(DocumentTransferReceivedLine::getDocumentLineId, Function.identity()));
        Set<UUID> seen = new HashSet<>();
        for (ReceiveTransferRequest.Item item : items) {
            if (!lineIds.contains(item.lineId())) {
                throw ApiException.badRequest("Linia nie należy do tego dokumentu");
            }
            if (!seen.add(item.lineId())) {
                throw ApiException.badRequest("Linia powtarza się w ilościach");
            }
            DocumentTransferReceivedLine line = saved.get(item.lineId());
            if (line == null) {
                receivedLines.save(new DocumentTransferReceivedLine(targetId, item.lineId(), item.quantity()));
            } else {
                line.setQuantity(item.quantity());
            }
        }
        AppUser user = user(userId);
        target.markReceived(user);
        if (target.getEnteredAt() == null) {
            target.markEntered(user, true);
        }
        receivedLines.flush();
        targets.flush();
        return incomingTransfer(storeId, targetId);
    }

    /** Plik przyjęcia do pobrania. */
    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    /**
     * Plik przyjęcia w ilościach, które naprawdę przyjechały: {@code proto} (PROTO6 dla programu sklepu, pochodzenie
     * = sklep wysyłający) albo {@code xlsx} (ilość z dokumentu, przyjęta i różnica).
     */
    @Transactional(readOnly = true)
    public ExportFile export(UUID storeId, UUID targetId, String format, String username) {
        DocumentTransferTarget target = incoming(storeId, targetId);
        Document document = withLines(target);
        Map<UUID, BigDecimal> received = receivedLines.findByTargetId(targetId).stream()
                .collect(Collectors.toMap(DocumentTransferReceivedLine::getDocumentLineId,
                        DocumentTransferReceivedLine::getQuantity));
        List<DocumentLine> lines = document.getLines().stream()
                .sorted(Comparator.comparingInt(DocumentLine::getLineNumber)).toList();
        String origin = document.getStore().getNumber();
        return switch (format) {
            case "proto" -> new ExportFile(Proto6.fileName(origin, username), Proto6.CONTENT_TYPE,
                    Proto6.content(lines.stream()
                            .map(line -> new Proto6.Line(line.getEan(), receivedQuantity(line, received)))
                            .toList()));
            case "xlsx" -> {
                List<List<Object>> rows = new ArrayList<>();
                for (DocumentLine line : lines) {
                    BigDecimal sent = line.getQuantity().abs();
                    BigDecimal arrived = receivedQuantity(line, received);
                    rows.add(List.of(line.getLineNumber(), nonNull(line.getEan()), nonNull(line.getItm8()),
                            nonNull(line.getName()), nonNull(line.getBrand()), nonNull(line.getUnit()), sent,
                            arrived, arrived.subtract(sent)));
                }
                yield new ExportFile("przyjecie-mm-" + origin + "-" + document.getNumber() + ".xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        xlsxWriter.write("Przyjęcie", List.of(
                                new XlsxWriter.Column("Lp.", 6),
                                new XlsxWriter.Column("EAN", 16),
                                new XlsxWriter.Column("ITM8", 16),
                                new XlsxWriter.Column("Nazwa", 42),
                                new XlsxWriter.Column("Marka", 20),
                                new XlsxWriter.Column("Jedn.", 8),
                                new XlsxWriter.Column("W dokumencie", 13),
                                new XlsxWriter.Column("Przyjęto", 11),
                                new XlsxWriter.Column("Różnica", 11)), rows));
            }
            default -> throw ApiException.notFound("Nieznany format pliku: " + format);
        };
    }

    /** W sklepie wysyłającym MM to wydanie, ilość bywa z minusem; bez wpisu przyjechało tyle, ile w dokumencie. */
    private static BigDecimal receivedQuantity(DocumentLine line, Map<UUID, BigDecimal> received) {
        return received.getOrDefault(line.getId(), line.getQuantity().abs());
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }

    private Document withLines(DocumentTransferTarget target) {
        return documents.findWithLines(target.getDocument().getStore().getId(), DocumentType.MM,
                        target.getDocument().getId())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    @Transactional
    public IncomingTransfer markEntered(UUID storeId, UUID targetId, boolean entered, UUID userId) {
        incoming(storeId, targetId).markEntered(user(userId), entered);
        targets.flush();
        return incomingTransfer(storeId, targetId);
    }

    private DocumentTransferTarget incoming(UUID storeId, UUID targetId) {
        return targets.findIncoming(storeId, targetId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    private Document transfer(UUID storeId, String typeCode, UUID documentId) {
        DocumentType type = DocumentType.fromCode(typeCode)
                .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
        Document document = documents.findHeader(storeId, type, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (type != DocumentType.MM) {
            throw ApiException.badRequest("Dla innego sklepu wystawia się ruch międzymagazynowy");
        }
        return document;
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
