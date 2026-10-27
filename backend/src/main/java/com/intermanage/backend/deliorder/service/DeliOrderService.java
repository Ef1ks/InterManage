package com.intermanage.backend.deliorder.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.deliorder.component.DeliOrderDocuments;
import com.intermanage.backend.deliorder.dto.CorrectDeliOrderRequest;
import com.intermanage.backend.deliorder.dto.DeliDocumentCandidate;
import com.intermanage.backend.deliorder.dto.DeliOrderResponse;
import com.intermanage.backend.deliorder.dto.DeliOrderSummaryResponse;
import com.intermanage.backend.deliorder.dto.DeliOverviewResponse;
import com.intermanage.backend.deliorder.dto.DeliSupplierResponse;
import com.intermanage.backend.deliorder.dto.OrderFormResponse;
import com.intermanage.backend.deliorder.dto.SubmitDeliOrderRequest;
import com.intermanage.backend.deliorder.entity.DeliFormInvitation;
import com.intermanage.backend.deliorder.entity.DeliOrder;
import com.intermanage.backend.deliorder.entity.DeliOrderItem;
import com.intermanage.backend.deliorder.entity.DeliOrderStatus;
import com.intermanage.backend.deliorder.entity.InvitationStatus;
import com.intermanage.backend.deliorder.entity.OrderCategoryProduct;
import com.intermanage.backend.deliorder.entity.OrderSection;
import com.intermanage.backend.deliorder.entity.OrderUnit;
import com.intermanage.backend.deliorder.mapper.DeliOrderMapper;
import com.intermanage.backend.deliorder.repository.DeliFormInvitationRepository;
import com.intermanage.backend.deliorder.repository.DeliOrderRepository;
import com.intermanage.backend.deliorder.repository.OrderCategoryProductRepository;
import com.intermanage.backend.deliorder.repository.OrderSectionRepository;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentSearch;
import com.intermanage.backend.product.dto.ProductSearchResponse;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.mapper.StoreMapper;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Zamówienia garmażu: sklep zamawia z formularza dostawcy (własnego albo takiego, do którego przyjął zaproszenie),
 * dostawca widzi przychodzące zamówienia, może poprawić ilości i oznacza je jako zrealizowane.
 */
@Service
public class DeliOrderService {

    /** Daty dostawy liczymy w czasie sklepów, a nie serwera. */
    public static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final int LIST_LIMIT = 200;
    /** Kategoria pozycji dopisanych spoza formularza, na końcu działu, który wybrał zamawiający. */
    public static final String EXTRA_CATEGORY = "Spoza formularza";

    private final DeliOrderRepository orders;
    private final DeliFormInvitationRepository invitations;
    private final OrderSectionRepository sections;
    private final OrderCategoryProductRepository formItems;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final ProductRepository products;
    private final DeliOrderFormService formService;
    private final DeliOrderMapper deliOrderMapper;
    private final StoreMapper storeMapper;
    private final DeliOrderDocuments documents;
    /** Dokumenty magazynowe sklepu (ruchy międzymagazynowe przypinane do zamówień). */
    private final DocumentRepository storeDocuments;

    public DeliOrderService(DeliOrderRepository orders, DeliFormInvitationRepository invitations,
            OrderSectionRepository sections, OrderCategoryProductRepository formItems, StoreRepository stores,
            AppUserRepository users, ProductRepository products, DeliOrderFormService formService,
            DeliOrderMapper deliOrderMapper, StoreMapper storeMapper, DeliOrderDocuments documents,
            DocumentRepository storeDocuments) {
        this.orders = orders;
        this.invitations = invitations;
        this.sections = sections;
        this.formItems = formItems;
        this.stores = stores;
        this.users = users;
        this.products = products;
        this.formService = formService;
        this.deliOrderMapper = deliOrderMapper;
        this.storeMapper = storeMapper;
        this.documents = documents;
        this.storeDocuments = storeDocuments;
    }

    @Transactional(readOnly = true)
    public DeliOverviewResponse overview(UUID storeId) {
        List<DeliSupplierResponse> suppliers = new ArrayList<>();
        if (!sections.findByStoreId(storeId).isEmpty()) {
            suppliers.add(new DeliSupplierResponse(storeMapper.toSummary(stores.getReferenceById(storeId)), true));
        }
        for (DeliFormInvitation invitation : invitations.findReceived(storeId, InvitationStatus.ACCEPTED)) {
            if (invitation.getFormStore().isActive()) {
                suppliers.add(new DeliSupplierResponse(storeMapper.toSummary(invitation.getFormStore()), false));
            }
        }
        return new DeliOverviewResponse(suppliers,
                orders.countActive(storeId, DeliOrderStatus.NEW),
                invitations.findReceived(storeId, InvitationStatus.PENDING).stream()
                        .map(deliOrderMapper::toResponse)
                        .toList());
    }

    @Transactional(readOnly = true)
    public OrderFormResponse supplierForm(UUID storeId, UUID formStoreId) {
        ensureCanOrderFrom(storeId, formStoreId);
        return formService.form(formStoreId);
    }

    /** Produkty z kartoteki dostawcy do dopisania jako pozycje spoza formularza. */
    @Transactional(readOnly = true)
    public List<ProductSearchResponse> searchSupplierProducts(UUID storeId, UUID formStoreId, String query) {
        ensureCanOrderFrom(storeId, formStoreId);
        return formService.searchProducts(formStoreId, query);
    }

    @Transactional
    public DeliOrderResponse submit(UUID storeId, UUID formStoreId, SubmitDeliOrderRequest request, UUID userId) {
        ensureCanOrderFrom(storeId, formStoreId);
        if (request.deliveryDate().isBefore(LocalDate.now(STORE_ZONE))) {
            throw ApiException.badRequest("Data dostawy nie może być w przeszłości");
        }

        if (request.itemsOrEmpty().isEmpty() && request.extrasOrEmpty().isEmpty()) {
            throw ApiException.badRequest("Wpisz ilość przy co najmniej jednym produkcie");
        }
        Map<UUID, BigDecimal> quantities = new HashMap<>();
        for (SubmitDeliOrderRequest.Line line : request.itemsOrEmpty()) {
            if (quantities.put(line.itemId(), line.quantity()) != null) {
                throw ApiException.badRequest("Ten sam produkt występuje w zamówieniu dwa razy");
            }
        }
        List<OrderCategoryProduct> items = quantities.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(formItems.findInForm(quantities.keySet(), formStoreId));
        if (items.size() != quantities.size()) {
            throw ApiException.conflict("Formularz zmienił się w trakcie wypełniania. Odśwież stronę i sprawdź ilości.");
        }
        // Kolejność jak w formularzu: dział, kategoria, produkt.
        items.sort((a, b) -> {
            int bySection = Integer.compare(a.getCategory().getSection().getPosition(),
                    b.getCategory().getSection().getPosition());
            if (bySection != 0) {
                return bySection;
            }
            int byCategory = Integer.compare(a.getCategory().getPosition(), b.getCategory().getPosition());
            return byCategory != 0 ? byCategory : Integer.compare(a.getPosition(), b.getPosition());
        });

        DeliOrder order = new DeliOrder(stores.getReferenceById(formStoreId), stores.getReferenceById(storeId),
                request.deliveryDate(), users.getReferenceById(userId));
        Map<UUID, List<SubmitDeliOrderRequest.Extra>> extrasBySection = new HashMap<>();
        Map<UUID, Product> extraProducts = loadExtras(formStoreId, request.extrasOrEmpty(), extrasBySection);
        // Dział po dziale: najpierw pozycje z formularza, potem dopisane spoza niego.
        for (OrderSection section : sections.findByStoreId(formStoreId)) {
            for (OrderCategoryProduct item : items) {
                if (item.getCategory().getSection().getId().equals(section.getId())) {
                    BigDecimal quantity = quantities.get(item.getId());
                    ensureFits(item.getOrderUnit(), quantity, item.getProduct().getName());
                    order.addItem(item.getProduct(), section.getName(), item.getCategory().getName(),
                            item.getOrderUnit(), quantity);
                }
            }
            for (SubmitDeliOrderRequest.Extra extra : extrasBySection.getOrDefault(section.getId(), List.of())) {
                Product product = extraProducts.get(extra.productId());
                ensureFits(extra.unit(), extra.quantity(), product.getName());
                order.addItem(product, section.getName(), EXTRA_CATEGORY, extra.unit(), extra.quantity());
            }
            extrasBySection.remove(section.getId());
        }
        if (!extrasBySection.isEmpty()) {
            throw ApiException.conflict("Formularz zmienił się w trakcie wypełniania. Odśwież stronę i sprawdź ilości.");
        }
        return deliOrderMapper.toResponse(orders.saveAndFlush(order));
    }

    /** Produkty spoza formularza z kartoteki dostawcy; pozycje rozkłada po działach do {@code bySection}. */
    private Map<UUID, Product> loadExtras(UUID formStoreId, List<SubmitDeliOrderRequest.Extra> extras,
            Map<UUID, List<SubmitDeliOrderRequest.Extra>> bySection) {
        Map<UUID, Product> found = new HashMap<>();
        if (extras.isEmpty()) {
            return found;
        }
        Set<UUID> productIds = new HashSet<>();
        for (SubmitDeliOrderRequest.Extra extra : extras) {
            if (!productIds.add(extra.productId())) {
                throw ApiException.badRequest("Ten sam dodatkowy produkt występuje w zamówieniu dwa razy");
            }
            bySection.computeIfAbsent(extra.sectionId(), key -> new ArrayList<>()).add(extra);
        }
        products.findByIdInAndStoreId(productIds, formStoreId).forEach(product -> found.put(product.getId(), product));
        if (found.size() != productIds.size()) {
            throw ApiException.badRequest("Dodatkowego produktu nie ma w kartotece dostawcy");
        }
        return found;
    }

    @Transactional(readOnly = true)
    public List<DeliOrderSummaryResponse> incoming(UUID storeId) {
        return orders.findIncoming(storeId, Limit.of(LIST_LIMIT)).stream().map(deliOrderMapper::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<DeliOrderSummaryResponse> sent(UUID storeId) {
        return orders.findSent(storeId, Limit.of(LIST_LIMIT)).stream().map(deliOrderMapper::toSummary).toList();
    }

    /** Zamówienie widzi sklep, który je złożył, i sklep-dostawca. */
    @Transactional(readOnly = true)
    public DeliOrderResponse order(UUID storeId, UUID orderId) {
        return deliOrderMapper.toResponse(loadVisible(storeId, orderId));
    }

    private DeliOrder loadVisible(UUID storeId, UUID orderId) {
        return orders.findById(orderId)
                .filter(o -> o.getStore().getId().equals(storeId) || o.getFormStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zamówienia"));
    }

    /** Plik zamówienia do pobrania: lista EAN, Excel albo PDF do wydruku. */
    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    @Transactional(readOnly = true)
    public ExportFile export(UUID storeId, UUID orderId, String format) {
        DeliOrder order = loadVisible(storeId, orderId);
        DeliOrderDocuments.OrderDocument document = new DeliOrderDocuments.OrderDocument(order.getId(),
                order.getStore().getNumber(), order.getStore().getName(), order.getFormStore().getNumber(),
                order.getFormStore().getName(), order.getSubmittedAt(),
                order.getSubmittedBy() != null ? order.getSubmittedBy().getDisplayName() : null, order.getDeliveryDate(),
                order.getItems().stream()
                        .map(item -> new DeliOrderDocuments.Line(item.getSectionName(), item.getCategoryName(),
                                item.getProduct().getEan(), item.getProduct().getName(),
                                item.getProduct().getBrand(), item.getUnit(),
                                item.getQuantity()))
                        .toList());
        String baseName = "zamowienie-" + order.getStore().getNumber() + "-" + order.getDeliveryDate() + "-nr" + order.getId();
        return switch (format) {
            case "lst" -> new ExportFile(baseName + ".lst", "text/plain;charset=US-ASCII", documents.lst(document));
            case "xlsx" -> new ExportFile(baseName + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", documents.xlsx(document));
            case "pdf" -> new ExportFile(baseName + ".pdf", "application/pdf", documents.pdf(document));
            default -> throw ApiException.notFound("Nieznany format pliku: " + format);
        };
    }

    @Transactional
    public DeliOrderResponse correct(UUID storeId, UUID orderId, CorrectDeliOrderRequest request, UUID userId) {
        DeliOrder order = loadIncoming(storeId, orderId);
        ensureNotDeleted(order);
        if (order.getStatus() != DeliOrderStatus.NEW) {
            throw ApiException.conflict("Zamówienie jest już zrealizowane - nie można zmieniać ilości");
        }
        Map<UUID, DeliOrderItem> byId = new HashMap<>();
        order.getItems().forEach(item -> byId.put(item.getId(), item));
        Set<UUID> seen = new HashSet<>();
        boolean changed = false;
        for (CorrectDeliOrderRequest.Line line : request.items()) {
            DeliOrderItem item = byId.get(line.id());
            if (item == null || !seen.add(line.id())) {
                throw ApiException.badRequest("Nieprawidłowa pozycja zamówienia");
            }
            ensureFits(item.getUnit(), line.quantity(), item.getProduct().getName());
            if (item.getQuantity().compareTo(line.quantity()) != 0) {
                item.setQuantity(line.quantity());
                changed = true;
            }
        }
        if (changed) {
            order.markCorrected(users.getReferenceById(userId));
        }
        return deliOrderMapper.toResponse(order);
    }

    /**
     * Usuwa nowe zamówienie tylko po stronie tego sklepu: znika z jego listy, a druga strona widzi status
     * "usunięte". Nic nie jest kasowane - {@link #restore} przywraca zamówienie. Sklep, który je złożył,
     * wycofuje je z uprawnieniem Podgląd, dostawca usuwa z uprawnieniem Edycja (jak poprawki i realizację).
     */
    @Transactional
    public DeliOrderResponse delete(UUID storeId, UUID orderId, boolean canEdit, UUID userId) {
        DeliOrder order = loadOwnSide(storeId, orderId, canEdit);
        if (order.getStatus() != DeliOrderStatus.NEW) {
            throw ApiException.conflict("Zamówienie jest już zrealizowane - nie można go usunąć");
        }
        AppUser by = users.getReferenceById(userId);
        // Przy zamówieniu z własnego formularza sklep jest obiema stronami naraz.
        if (order.getStore().getId().equals(storeId)) {
            order.markDeletedByStore(by);
        }
        if (order.getFormStore().getId().equals(storeId)) {
            order.markDeletedByFormStore(by);
        }
        return deliOrderMapper.toResponse(order);
    }

    /** Cofa usunięcie po stronie tego sklepu. */
    @Transactional
    public DeliOrderResponse restore(UUID storeId, UUID orderId, boolean canEdit) {
        DeliOrder order = loadOwnSide(storeId, orderId, canEdit);
        if (order.getStore().getId().equals(storeId)) {
            order.restoreForStore();
        }
        if (order.getFormStore().getId().equals(storeId)) {
            order.restoreForFormStore();
        }
        return deliOrderMapper.toResponse(order);
    }

    /** Zamówienie, którym sklep zarządza po swojej stronie: wysłane zawsze, przychodzące z Edycją. */
    private DeliOrder loadOwnSide(UUID storeId, UUID orderId, boolean canEdit) {
        DeliOrder order = loadVisible(storeId, orderId);
        if (!order.getStore().getId().equals(storeId) && !canEdit) {
            throw ApiException.forbidden(
                    "Przychodzące zamówienie może usunąć albo przywrócić osoba z uprawnieniem do edycji");
        }
        return order;
    }

    /** Usuniętego po którejkolwiek stronie zamówienia nie poprawiamy ani nie realizujemy. */
    private static void ensureNotDeleted(DeliOrder order) {
        if (order.getStoreDeletedAt() != null) {
            throw ApiException.conflict("Sklep wycofał to zamówienie - nie można go poprawiać ani realizować");
        }
        if (order.getFormStoreDeletedAt() != null) {
            throw ApiException.conflict("Zamówienie jest usunięte - przywróć je, żeby je poprawiać albo realizować");
        }
    }

    @Transactional
    public DeliOrderResponse fulfill(UUID storeId, UUID orderId, UUID userId) {
        DeliOrder order = loadIncoming(storeId, orderId);
        ensureNotDeleted(order);
        if (order.getStatus() == DeliOrderStatus.FULFILLED) {
            throw ApiException.conflict("Zamówienie jest już oznaczone jako zrealizowane");
        }
        order.markFulfilled(users.getReferenceById(userId));
        return deliOrderMapper.toResponse(order);
    }

    /** Ile podpowiedzi dokumentów pokazujemy naraz przy przypinaniu. */
    private static final int DOCUMENT_CANDIDATES = 20;

    /**
     * Ruchy międzymagazynowe dostawcy do przypięcia. Bez zapytania: z okolic daty dostawy (tydzień przed do dwóch dni
     * po), najnowsze pierwsze; z zapytaniem - wszystkie pasujące do numeru.
     */
    @Transactional(readOnly = true)
    public List<DeliDocumentCandidate> documentCandidates(UUID storeId, UUID orderId, String query) {
        DeliOrder order = loadIncoming(storeId, orderId);
        List<String> words = DocumentSearch.words(query);
        Specification<Document> matching = DocumentSearch.inStoreMatching(storeId, DocumentType.MM, words);
        if (words.isEmpty()) {
            matching = matching.and(DocumentSearch.dated(order.getDeliveryDate().minusDays(7).atStartOfDay(),
                    order.getDeliveryDate().plusDays(3).atStartOfDay()));
        }
        Set<UUID> linked = new HashSet<>();
        order.getDocuments().forEach(link -> linked.add(link.getDocument().getId()));
        return storeDocuments.findAll(matching, PageRequest.of(0, DOCUMENT_CANDIDATES,
                        Sort.by(Sort.Order.desc("documentDate"), Sort.Order.desc("id"))))
                .stream()
                .map(document -> new DeliDocumentCandidate(document.getId(), document.getNumber(),
                        document.getDocumentDate(), document.getLineCount(), linked.contains(document.getId())))
                .toList();
    }

    /** Dostawca przypina swój dokument ruchu międzymagazynowego, na którym jedzie zamówienie. */
    @Transactional
    public DeliOrderResponse linkDocument(UUID storeId, UUID orderId, UUID documentId, UUID userId) {
        DeliOrder order = loadIncoming(storeId, orderId);
        ensureNotDeleted(order);
        Document document = storeDocuments.findById(documentId)
                .filter(d -> d.getType() == DocumentType.MM && d.getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu ruchu międzymagazynowego"));
        if (order.linkDocument(document, users.getReferenceById(userId)) == null) {
            throw ApiException.conflict("Ten dokument jest już przypięty do zamówienia");
        }
        orders.flush();
        return deliOrderMapper.toResponse(order);
    }

    /** Odpięcie zostawia wpis w historii (kto i kiedy odpiął); ponowne przypięcie przywraca dokument. */
    @Transactional
    public DeliOrderResponse unlinkDocument(UUID storeId, UUID orderId, UUID documentId, UUID userId) {
        DeliOrder order = loadIncoming(storeId, orderId);
        if (!order.unlinkDocument(documentId, users.getReferenceById(userId))) {
            throw ApiException.notFound("Ten dokument nie jest przypięty do zamówienia");
        }
        return deliOrderMapper.toResponse(order);
    }

    private DeliOrder loadIncoming(UUID storeId, UUID orderId) {
        return orders.findById(orderId)
                .filter(o -> o.getFormStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zamówienia"));
    }

    /** Ze swojego formularza sklep zamawia zawsze, z cudzego tylko po przyjęciu zaproszenia. */
    private void ensureCanOrderFrom(UUID storeId, UUID formStoreId) {
        boolean own = storeId.equals(formStoreId);
        boolean invited = !own && invitations.existsByFormStoreIdAndStoreIdAndStatus(formStoreId, storeId,
                InvitationStatus.ACCEPTED) && stores.findById(formStoreId).map(Store::isActive).orElse(false);
        if (!own && !invited) {
            throw ApiException.notFound("Nie masz dostępu do formularza tego sklepu");
        }
    }

    private static void ensureFits(OrderUnit unit, BigDecimal quantity, String productName) {
        if (!unit.accepts(quantity)) {
            String name = productName != null ? productName : "produktu";
            throw ApiException.badRequest(unit == OrderUnit.SZT
                    ? "Ilość " + name + " zamawia się w całych sztukach"
                    : "Ilość " + name + " może mieć najwyżej 3 miejsca po przecinku");
        }
    }
}
