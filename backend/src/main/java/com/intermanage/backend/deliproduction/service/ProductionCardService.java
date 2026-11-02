package com.intermanage.backend.deliproduction.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.deliproduction.component.ProductionPdf;
import com.intermanage.backend.deliproduction.dto.ProductionCardRequest;
import com.intermanage.backend.deliproduction.dto.ProductionCardResponse;
import com.intermanage.backend.deliproduction.dto.ProductionCardSummary;
import com.intermanage.backend.deliproduction.entity.DocumentProductionCard;
import com.intermanage.backend.deliproduction.entity.ProductionArticle;
import com.intermanage.backend.deliproduction.entity.ProductionCard;
import com.intermanage.backend.deliproduction.entity.ProductionCardLine;
import com.intermanage.backend.deliproduction.mapper.ProductionMapper;
import com.intermanage.backend.deliproduction.repository.DocumentProductionCardRepository;
import com.intermanage.backend.deliproduction.repository.ProductionArticleRepository;
import com.intermanage.backend.deliproduction.repository.ProductionCardRepository;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentTransferTarget;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentTransferTargetRepository;
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.entity.StorePartner;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Karty produkcji: zapis z ilościami ze skanera, lista sklepu i sklepów zaprzyjaźnionych, wydruk i przypinanie do
 * ruchu międzymagazynowego (MM), którym wyroby jadą do innego sklepu. Pozycja karty kopiuje parametry schematu
 * z chwili dodania, więc późniejsza zmiana bazy nie zmienia zapisanych kart. Usunięcie i odpięcie to znaczniki.
 */
@Service
public class ProductionCardService {

    public enum Scope {
        OWN, PARTNERS
    }

    private static final int LIST_DAYS = 366;
    /** Podpowiedzi kart do MM: z dwóch tygodni przed dokumentem do dwóch dni po nim. */
    private static final int CANDIDATE_DAYS_BEFORE = 14;
    private static final int CANDIDATE_DAYS_AFTER = 2;

    private final ProductionCardRepository cards;
    private final ProductionArticleRepository articles;
    private final DocumentProductionCardRepository documentCards;
    private final DocumentRepository documents;
    private final DocumentTransferTargetRepository transfers;
    private final StorePartnerRepository partners;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final ProductionBaseService base;
    private final ProductionPdf pdf;
    private final ProductionMapper mapper;

    public ProductionCardService(ProductionCardRepository cards, ProductionArticleRepository articles,
            DocumentProductionCardRepository documentCards, DocumentRepository documents,
            DocumentTransferTargetRepository transfers, StorePartnerRepository partners, StoreRepository stores,
            AppUserRepository users, ProductionBaseService base, ProductionPdf pdf, ProductionMapper mapper) {
        this.cards = cards;
        this.articles = articles;
        this.documentCards = documentCards;
        this.documents = documents;
        this.transfers = transfers;
        this.partners = partners;
        this.stores = stores;
        this.users = users;
        this.base = base;
        this.pdf = pdf;
        this.mapper = mapper;
    }

    /** Karty sklepu albo jego sklepów zaprzyjaźnionych z okresu (data produkcji), od najnowszej. */
    @Transactional(readOnly = true)
    public List<ProductionCardSummary> list(UUID storeId, LocalDate from, LocalDate to, Scope scope) {
        if (from.isAfter(to) || from.plusDays(LIST_DAYS).isBefore(to)) {
            throw ApiException.badRequest("Wybierz okres do roku");
        }
        Collection<UUID> storeIds = scope == Scope.OWN ? List.of(storeId) : partnerStoreIds(storeId);
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return summaries(cards.findInPeriod(storeIds, from, to));
    }

    /** Karta sklepu albo sklepu zaprzyjaźnionego (tylko do podglądu). */
    @Transactional(readOnly = true)
    public ProductionCardResponse get(UUID storeId, UUID cardId) {
        ProductionCard card = visible(storeId, cardId);
        return mapper.toResponse(card, store(card.getStoreId()), card.getStoreId().equals(storeId));
    }

    @Transactional
    public ProductionCardResponse create(UUID storeId, ProductionCardRequest request, UUID userId) {
        AppUser by = users.getReferenceById(userId);
        ProductionCard card = new ProductionCard(storeId, by);
        card.update(request.productionDate(), lines(storeId, request.lines(), Map.of()), by);
        cards.save(card);
        return mapper.toResponse(card, store(storeId), true);
    }

    @Transactional
    public ProductionCardResponse update(UUID storeId, UUID cardId, ProductionCardRequest request, UUID userId) {
        ProductionCard card = own(storeId, cardId);
        if (card.isDeleted()) {
            throw ApiException.conflict("Karta jest usunięta. Najpierw ją przywróć.");
        }
        // Pozycje, które już były na karcie, zachowują swoje parametry (kopię z chwili dodania).
        Map<UUID, ProductionCardLine> previous = card.getLines().stream()
                .collect(Collectors.toMap(line -> line.getArticle().getId(), Function.identity(),
                        (first, second) -> first));
        AppUser by = users.getReferenceById(userId);
        card.update(request.productionDate(), lines(storeId, request.lines(), previous), by);
        return mapper.toResponse(card, store(storeId), true);
    }

    @Transactional
    public void delete(UUID storeId, UUID cardId, UUID userId) {
        ProductionCard card = own(storeId, cardId);
        if (!card.isDeleted()) {
            card.delete(users.getReferenceById(userId));
        }
    }

    @Transactional
    public ProductionCardResponse restore(UUID storeId, UUID cardId) {
        ProductionCard card = own(storeId, cardId);
        card.restore();
        return mapper.toResponse(card, store(storeId), true);
    }

    /** PDF karty sklepu albo sklepu zaprzyjaźnionego. */
    @Transactional(readOnly = true)
    public DocumentService.ExportFile pdf(UUID storeId, UUID cardId) {
        return pdfFile(visible(storeId, cardId));
    }

    /** Karty przypięte do ruchu międzymagazynowego sklepu. */
    @Transactional(readOnly = true)
    public List<ProductionCardSummary> documentCards(UUID storeId, String typeCode, UUID documentId) {
        Document document = transferDocument(storeId, typeCode, documentId);
        return summaries(documentCards.findActive(document.getId()).stream().map(DocumentProductionCard::getCard)
                .toList());
    }

    /** Karty sklepu do przypięcia: z dwóch tygodni przed dokumentem do dwóch dni po nim, bez już przypiętych. */
    @Transactional(readOnly = true)
    public List<ProductionCardSummary> candidates(UUID storeId, String typeCode, UUID documentId) {
        Document document = transferDocument(storeId, typeCode, documentId);
        LocalDate day = document.getDocumentDate().toLocalDate();
        Set<UUID> linked = documentCards.findActive(document.getId()).stream()
                .map(link -> link.getCard().getId()).collect(Collectors.toSet());
        return summaries(cards.findInPeriod(List.of(storeId), day.minusDays(CANDIDATE_DAYS_BEFORE),
                day.plusDays(CANDIDATE_DAYS_AFTER)).stream()
                .filter(card -> !linked.contains(card.getId()))
                .toList());
    }

    @Transactional
    public List<ProductionCardSummary> link(UUID storeId, String typeCode, UUID documentId, UUID cardId,
            UUID userId) {
        Document document = transferDocument(storeId, typeCode, documentId);
        ProductionCard card = own(storeId, cardId);
        if (card.isDeleted()) {
            throw ApiException.conflict("Karta jest usunięta");
        }
        if (documentCards.findActive(document.getId(), cardId).isEmpty()) {
            documentCards.save(new DocumentProductionCard(document, card, users.getReferenceById(userId)));
        }
        return documentCards(storeId, typeCode, documentId);
    }

    /** Odpięcie (znacznik); "Przywróć" to ponowne przypięcie. */
    @Transactional
    public List<ProductionCardSummary> unlink(UUID storeId, String typeCode, UUID documentId, UUID cardId,
            UUID userId) {
        Document document = transferDocument(storeId, typeCode, documentId);
        documentCards.findActive(document.getId(), cardId)
                .orElseThrow(() -> ApiException.notFound("Karta nie jest przypięta do dokumentu"))
                .unlink(users.getReferenceById(userId));
        return documentCards(storeId, typeCode, documentId);
    }

    /** PDF karty przypiętej do dokumentu (dla osób z dostępem do dokumentów, bez uprawnienia do kart). */
    @Transactional(readOnly = true)
    public DocumentService.ExportFile documentCardPdf(UUID storeId, String typeCode, UUID documentId, UUID cardId) {
        Document document = transferDocument(storeId, typeCode, documentId);
        return pdfFile(linkedCard(document.getId(), cardId));
    }

    /** Karty przy ruchu międzymagazynowym, który sklep docelowy ma do wprowadzenia. */
    @Transactional(readOnly = true)
    public List<ProductionCardSummary> incomingCards(UUID storeId, UUID transferId) {
        DocumentTransferTarget transfer = incoming(storeId, transferId);
        return summaries(documentCards.findActive(transfer.getDocument().getId()).stream()
                .map(DocumentProductionCard::getCard).toList());
    }

    @Transactional(readOnly = true)
    public DocumentService.ExportFile incomingCardPdf(UUID storeId, UUID transferId, UUID cardId) {
        DocumentTransferTarget transfer = incoming(storeId, transferId);
        return pdfFile(linkedCard(transfer.getDocument().getId(), cardId));
    }

    private ProductionCard linkedCard(UUID documentId, UUID cardId) {
        return documentCards.findActive(documentId).stream()
                .map(DocumentProductionCard::getCard)
                .filter(card -> card.getId().equals(cardId))
                .findFirst()
                .flatMap(card -> cards.findWithLines(card.getId()))
                .orElseThrow(() -> ApiException.notFound("Karta nie jest przypięta do dokumentu"));
    }

    private DocumentService.ExportFile pdfFile(ProductionCard card) {
        List<ProductionPdf.CardLine> lines = card.getLines().stream()
                .map(line -> new ProductionPdf.CardLine(line.getName(), line.getStartHour(), line.getEndHour(),
                        line.getParameters().bakingTemperature(), line.getParameters().internalTemperature(),
                        line.getQuantity(), line.getParameters().coolingTime(),
                        line.getParameters().coolingTemperature(), line.expiryDate(card.getProductionDate())))
                .toList();
        byte[] content = pdf.card(new ProductionPdf.Card(card.getProductionDate(), base.stamp(card.getStoreId()),
                lines));
        Store store = store(card.getStoreId());
        String fileName = "karta-produkcji-" + store.getNumber() + "-"
                + DateTimeFormatter.BASIC_ISO_DATE.format(card.getProductionDate()) + ".pdf";
        return new DocumentService.ExportFile(fileName, "application/pdf", content);
    }

    private List<ProductionCardLine> lines(UUID storeId, List<ProductionCardRequest.Line> requested,
            Map<UUID, ProductionCardLine> previous) {
        Set<UUID> missing = requested.stream()
                .map(ProductionCardRequest.Line::articleId)
                .filter(id -> !previous.containsKey(id))
                .collect(Collectors.toSet());
        Map<UUID, ProductionArticle> active = missing.isEmpty() ? Map.of()
                : articles.findActive(storeId, missing).stream()
                        .collect(Collectors.toMap(ProductionArticle::getId, Function.identity()));
        if (active.size() != missing.size()) {
            throw ApiException.badRequest("Części artykułów nie ma w bazie kart produkcji. Odśwież stronę.");
        }
        List<ProductionCardLine> lines = new ArrayList<>();
        for (ProductionCardRequest.Line line : requested) {
            ProductionCardLine old = previous.get(line.articleId());
            BigDecimal quantity = line.quantity().stripTrailingZeros();
            if (old != null) {
                lines.add(new ProductionCardLine(old.getArticle(), old.getCode(), old.getName(), old.getParameters(),
                        quantity, line.startHour()));
            } else {
                ProductionArticle article = active.get(line.articleId());
                lines.add(new ProductionCardLine(article, article.getCode(), article.getName(),
                        article.getSchema().getParameters(), quantity, line.startHour()));
            }
        }
        return lines;
    }

    private List<ProductionCardSummary> summaries(List<ProductionCard> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, Object[]> counts = new HashMap<>();
        for (Object[] row : cards.countLines(list.stream().map(ProductionCard::getId).toList())) {
            counts.put((UUID) row[0], row);
        }
        Map<UUID, Store> storeById = stores.findAllById(list.stream().map(ProductionCard::getStoreId)
                .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Store::getId, Function.identity()));
        return list.stream().map(card -> {
            Object[] row = counts.get(card.getId());
            long lineCount = row != null ? ((Number) row[1]).longValue() : 0;
            BigDecimal total = row != null ? (BigDecimal) row[2] : BigDecimal.ZERO;
            return mapper.toSummary(card, storeById.get(card.getStoreId()), lineCount, total.stripTrailingZeros());
        }).toList();
    }

    private Collection<UUID> partnerStoreIds(UUID storeId) {
        return partners.findActive(storeId).stream()
                .filter(StorePartner::isAccepted)
                .map(partner -> partner.getStore().getId().equals(storeId)
                        ? partner.getPartnerStore().getId()
                        : partner.getStore().getId())
                .collect(Collectors.toSet());
    }

    /** Karta bieżącego sklepu albo (aktywna) sklepu zaprzyjaźnionego. */
    private ProductionCard visible(UUID storeId, UUID cardId) {
        ProductionCard card = cards.findWithLines(cardId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono karty produkcji"));
        if (card.getStoreId().equals(storeId)) {
            return card;
        }
        if (card.isDeleted() || !partners.arePartners(storeId, card.getStoreId())) {
            throw ApiException.notFound("Nie znaleziono karty produkcji");
        }
        return card;
    }

    private ProductionCard own(UUID storeId, UUID cardId) {
        return cards.findWithLines(cardId)
                .filter(card -> card.getStoreId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono karty produkcji"));
    }

    private Document transferDocument(UUID storeId, String typeCode, UUID documentId) {
        DocumentType type = DocumentType.fromCode(typeCode)
                .filter(found -> found == DocumentType.MM)
                .orElseThrow(() -> ApiException.badRequest("Karty produkcji przypina się do ruchów międzymagazynowych"));
        return documents.findHeader(storeId, type, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    private DocumentTransferTarget incoming(UUID storeId, UUID transferId) {
        return transfers.findIncoming(storeId, transferId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }
}
