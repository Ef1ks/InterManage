package com.intermanage.backend.document.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.CorrectedDelivery.CorrectedLine;
import com.intermanage.backend.document.dto.CorrectionCandidate;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.IncomingTransfer;
import com.intermanage.backend.document.dto.IncomingTransfers;
import com.intermanage.backend.document.dto.ReceiveTransferRequest;
import com.intermanage.backend.document.entity.DeliveryCorrection;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentTransferReceipt;
import com.intermanage.backend.document.entity.DocumentTransferReceivedLine;
import com.intermanage.backend.document.entity.DocumentTransferTarget;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.mapper.DocumentMapper;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentSearch;
import com.intermanage.backend.document.repository.DocumentTransferReceiptRepository;
import com.intermanage.backend.document.repository.DocumentTransferReceivedLineRepository;
import com.intermanage.backend.document.repository.DocumentTransferTargetRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Ruch międzymagazynowy (MM) wystawiony dla sklepu zaprzyjaźnionego. Sklep wysyłający wskazuje sklep docelowy
 * (tylko zaakceptowany sklep zaprzyjaźniony), a sklep docelowy widzi dokument z liniami do wprowadzenia u siebie,
 * oznacza go jako wprowadzony i przypina swój dokument przyjęcia: dopiero to kończy czekanie po obu stronach. Linie,
 * wartości i pliki sklepu docelowego są po poprawach sklepu wysyłającego. Nic nie jest kasowane: cofnięcie to znacznik,
 * zmiana sklepu cofa poprzedni wpis.
 */
@Service
public class DocumentTransferService {

    /** Ile ostatnio wprowadzonych dokumentów pokazuje sklep docelowy pod czekającymi. */
    static final int RECENTLY_ENTERED = 30;
    /** Dokumentu przyjęcia szukamy najwyżej tyle dni po dokumencie sklepu wysyłającego. */
    static final int RECEIPT_LOOKAHEAD_DAYS = 30;
    private static final int RECEIPT_CANDIDATES = 30;

    private final DocumentRepository documents;
    private final DocumentTransferTargetRepository targets;
    private final StoreRepository stores;
    private final StorePartnerRepository partners;
    private final AppUserRepository users;
    private final DocumentService documentService;
    private final DocumentMapper documentMapper;
    private final DocumentTransferReceivedLineRepository receivedLines;
    private final XlsxWriter xlsxWriter;
    private final DeliveryCorrectionService corrections;
    private final DocumentTransferReceiptRepository receipts;

    public DocumentTransferService(DocumentRepository documents, DocumentTransferTargetRepository targets,
            StoreRepository stores, StorePartnerRepository partners, AppUserRepository users,
            DocumentService documentService, DocumentMapper documentMapper,
            DocumentTransferReceivedLineRepository receivedLines, XlsxWriter xlsxWriter,
            DeliveryCorrectionService corrections, DocumentTransferReceiptRepository receipts) {
        this.documents = documents;
        this.targets = targets;
        this.stores = stores;
        this.partners = partners;
        this.users = users;
        this.documentService = documentService;
        this.documentMapper = documentMapper;
        this.receivedLines = receivedLines;
        this.xlsxWriter = xlsxWriter;
        this.corrections = corrections;
        this.receipts = receipts;
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

    /** Dokumenty innych sklepów wystawione dla sklepu: czekające (wszystkie) i ostatnio potwierdzone. */
    @Transactional(readOnly = true)
    public IncomingTransfers incoming(UUID storeId) {
        List<DocumentTransferTarget> pending = targets.findPending(storeId);
        List<DocumentTransferTarget> entered = targets.findEntered(storeId, Limit.of(RECENTLY_ENTERED));
        List<Document> all = new ArrayList<>();
        pending.forEach(target -> all.add(target.getDocument()));
        entered.forEach(target -> all.add(target.getDocument()));
        Map<UUID, List<DeliveryCorrection>> fixes = corrections.correctionsOf(all);
        return new IncomingTransfers(
                pending.stream().map(target -> summary(target, fixes)).toList(),
                entered.stream().map(target -> summary(target, fixes)).toList());
    }

    /** Pozycja listy: wartości po poprawach, bez linii. */
    private IncomingTransfer summary(DocumentTransferTarget target, Map<UUID, List<DeliveryCorrection>> fixes) {
        Document document = target.getDocument();
        List<DeliveryCorrection> links = fixes.getOrDefault(document.getId(), List.of());
        if (links.isEmpty()) {
            return documentMapper.toIncoming(target, document.getLineCount(), document.getPurchaseValue(),
                    document.getSaleValueGross(), List.of(), List.of(), List.of());
        }
        return response(target, corrections.transferLines(withLines(target), links), links, false);
    }

    private IncomingTransfer response(DocumentTransferTarget target, List<CorrectedLine> lines,
            List<DeliveryCorrection> links, boolean details) {
        return documentMapper.toIncoming(target, lines.size(),
                lines.stream().map(CorrectedLine::purchaseValue).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                lines.stream().map(CorrectedLine::saleValueGross).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                links.stream().map(link -> link.getCorrection().getNumber()).toList(),
                details ? lines : List.of(),
                details ? receivedLines.findByTargetId(target.getId()) : List.of());
    }

    /** Ile dokumentów czeka na wprowadzenie i przypięcie dokumentu przyjęcia (plakietka w menu). */
    @Transactional(readOnly = true)
    public long pendingCount(UUID storeId) {
        return targets.countPending(storeId);
    }

    /** Dokument innego sklepu z liniami; sklep docelowy nie ma dostępu do sklepu wysyłającego, tylko do tego wpisu. */
    @Transactional(readOnly = true)
    public IncomingTransfer incomingTransfer(UUID storeId, UUID targetId) {
        DocumentTransferTarget target = incoming(storeId, targetId);
        Document document = withLines(target);
        List<DeliveryCorrection> links = fixes(document);
        return response(target, corrections.transferLines(document, links), links, true);
    }

    private List<DeliveryCorrection> fixes(Document document) {
        return corrections.correctionsOf(List.of(document)).getOrDefault(document.getId(), List.of());
    }

    /** Linie, które sklep docelowy dostał: dokument po poprawach sklepu wysyłającego. */
    private List<CorrectedLine> sentLines(Document document) {
        return corrections.transferLines(document, fixes(document));
    }

    /**
     * MM na plus sklepu docelowego z okolic dokumentu, do przypięcia jako przyjęcie: najpierw z największą liczbą
     * wspólnych artykułów. Dokumenty przypięte do innego ruchu i poprawy własnych MM odpadają.
     */
    @Transactional(readOnly = true)
    public List<CorrectionCandidate> receiptCandidates(UUID storeId, UUID targetId, String query) {
        DocumentTransferTarget target = incoming(storeId, targetId);
        Document sent = withLines(target);
        Set<String> items = sentLines(sent).stream().map(CorrectedLine::itm8).collect(Collectors.toSet());
        LocalDateTime day = sent.getDocumentDate().toLocalDate().atStartOfDay();
        List<Document> found = documents.findDatedWithLines(storeId, DocumentType.MM, day.minusDays(1),
                day.plusDays(RECEIPT_LOOKAHEAD_DAYS + 1L)).stream().filter(Document::isCorrection).toList();
        Set<UUID> taken = new HashSet<>(corrections.deliveriesOf(found).keySet());
        if (!found.isEmpty()) {
            receipts.findActiveByDocumentIds(found.stream().map(Document::getId).toList())
                    .forEach(receipt -> taken.add(receipt.getDocument().getId()));
        }
        List<String> words = DocumentSearch.words(query);
        return found.stream()
                .filter(document -> !taken.contains(document.getId()) && words.stream()
                        .allMatch(document.getNumber().toLowerCase(Locale.ROOT)::contains))
                .map(document -> new CorrectionCandidate(document.getId(), document.getType().name(),
                        document.getType().singular(), document.getNumber(), document.getDocumentDate(),
                        document.getSupplierName(), document.getSupplierDocumentNumber(), document.getLineCount(),
                        document.getPurchaseValue(), (int) document.getLines().stream().map(DocumentLine::getItm8)
                                .distinct().filter(items::contains).count()))
                .sorted(Comparator.comparing(CorrectionCandidate::commonItems).reversed()
                        .thenComparing(CorrectionCandidate::documentDate))
                .limit(RECEIPT_CANDIDATES)
                .toList();
    }

    /**
     * Sklep docelowy przypina swój dokument przyjęcia; to kończy czekanie, a skoro dokument jest już w raporcie, ruch
     * jest też wprowadzony. Ten sam dokument przypięty ponownie niczego nie zmienia.
     */
    @Transactional
    public IncomingTransfer attachReceipt(UUID storeId, UUID targetId, UUID documentId, UUID userId) {
        DocumentTransferTarget target = incoming(storeId, targetId);
        Document document = documents.findHeader(storeId, DocumentType.MM, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu w tym sklepie"));
        DocumentTransferReceipt current = target.getReceipt();
        if (current != null) {
            if (current.getDocument().getId().equals(documentId)) {
                return incomingTransfer(storeId, targetId);
            }
            throw ApiException.conflict("Ten ruch ma już dokument przyjęcia " + current.getDocument().getNumber()
                    + ". Najpierw go odepnij.");
        }
        if (!document.isCorrection()) {
            throw ApiException.badRequest("Przyjęcie od innego sklepu to ruch międzymagazynowy z zakupem na plus");
        }
        if (!receipts.findActiveByDocumentIds(List.of(documentId)).isEmpty()) {
            throw ApiException.conflict("Dokument " + document.getNumber() + " potwierdza już inny ruch");
        }
        if (corrections.deliveriesOf(List.of(document)).containsKey(documentId)) {
            throw ApiException.conflict("Dokument " + document.getNumber()
                    + " jest poprawą ruchu międzymagazynowego tego sklepu");
        }
        AppUser user = user(userId);
        target.attachReceipt(document, user);
        if (target.getEnteredAt() == null) {
            target.markEntered(user, true);
        }
        targets.flush();
        return incomingTransfer(storeId, targetId);
    }

    /** Odpięcie zostawia wpis; dokument wraca do czekających, a ponowne przypięcie to „Przywróć”. */
    @Transactional
    public IncomingTransfer detachReceipt(UUID storeId, UUID targetId, UUID userId) {
        DocumentTransferReceipt receipt = incoming(storeId, targetId).getReceipt();
        if (receipt == null) {
            throw ApiException.notFound("Ten ruch nie ma przypiętego dokumentu przyjęcia");
        }
        receipt.unlink(user(userId));
        targets.flush();
        return incomingTransfer(storeId, targetId);
    }

    /**
     * Zapisuje ilości, które naprawdę przyjechały (linia pominięta = ilość z dokumentu, poprawka nadpisuje
     * poprzednią), i oznacza dokument jako wprowadzony. Z tych ilości powstaje plik PROTO6 i Excel.
     */
    @Transactional
    public IncomingTransfer receive(UUID storeId, UUID targetId, List<ReceiveTransferRequest.Item> items,
            UUID userId) {
        DocumentTransferTarget target = incoming(storeId, targetId);
        Set<UUID> lineIds = sentLines(withLines(target)).stream().map(line -> UUID.fromString(line.key()))
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
        List<CorrectedLine> lines = sentLines(document).stream()
                .sorted(Comparator.comparingInt(CorrectedLine::lineNumber)).toList();
        String origin = document.getStore().getNumber();
        return switch (format) {
            case "proto" -> new ExportFile(ProtoFile.fileName(6, origin, username), ProtoFile.CONTENT_TYPE,
                    ProtoFile.content(lines.stream()
                            .map(line -> new ProtoFile.Line(line.ean(), receivedQuantity(line, received)))
                            .toList()));
            case "xlsx" -> {
                List<List<Object>> rows = new ArrayList<>();
                for (CorrectedLine line : lines) {
                    BigDecimal sent = line.quantity().abs();
                    BigDecimal arrived = receivedQuantity(line, received);
                    rows.add(List.of(line.lineNumber(), nonNull(line.ean()), nonNull(line.itm8()),
                            nonNull(line.name()), nonNull(line.brand()), nonNull(line.unit()), sent,
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
    private static BigDecimal receivedQuantity(CorrectedLine line, Map<UUID, BigDecimal> received) {
        return received.getOrDefault(UUID.fromString(line.key()), line.quantity().abs());
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
