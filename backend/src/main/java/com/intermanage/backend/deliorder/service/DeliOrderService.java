package com.intermanage.backend.deliorder.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    private static final DateTimeFormatter PROTO_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * {@code username} trafia do nazwy pliku PROTO6: {@code PROTO6_RRRRMMDD_HHMMSS_POCHODZENIE_UZYTKOWNIK.Traite0},
     * gdzie pochodzenie to numer sklepu zamawiającego, a czas to chwila wygenerowania pliku.
     */
    @Transactional(readOnly = true)
    public ExportFile export(UUID storeId, UUID orderId, String format, String username) {
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
            case "proto" -> new ExportFile(
                    "PROTO6_" + ZonedDateTime.now(STORE_ZONE).format(PROTO_TIME) + "_" + order.getStore().getNumber()
                            + "_" + username.replaceAll("[^A-Za-z0-9._-]", "_") + ".Traite0",
                    "text/plain;charset=US-ASCII", documents.proto(document));
            default -> throw ApiException.notFound("Nieznany format pliku: " + format);
        };
    }

    /**
     * Otwarcie zamówienia u dostawcy: nowe staje się odczytane. Zamówienie z własnego formularza oglądane przez osobę,
     * która je złożyła, zostaje nowe - nikt inny go jeszcze nie widział.
     */
    @Transactional
    public DeliOrderResponse markRead(UUID storeId, UUID orderId, UUID userId) {
        DeliOrder order = loadIncoming(storeId, orderId);
        boolean ownOrder = order.getStore().getId().equals(storeId) && order.getSubmittedBy() != null
                && order.getSubmittedBy().getId().equals(userId);
        if (!ownOrder) {
            order.markRead(users.getReferenceById(userId));
        }
        return deliOrderMapper.toResponse(order);
    }

    /**
     * Zmiana daty dostawy niezrealizowanego zamówienia, np. po pomyłce przy składaniu. Zamawiający zmienia ją
     * z Podglądem, dostawca z Edycją.
     */
    @Transactional
    public DeliOrderResponse changeDeliveryDate(UUID storeId, UUID orderId, LocalDate deliveryDate, boolean canEdit,
            UUID userId) {
        DeliOrder order = loadOwnSide(storeId, orderId, canEdit);
        ensureActive(order);
        if (!order.getStatus().isOpen()) {
            throw ApiException.conflict("Zamówienie jest już zrealizowane - nie można zmienić daty dostawy");
        }
        if (deliveryDate.isBefore(LocalDate.now(STORE_ZONE))) {
            throw ApiException.badRequest("Data dostawy nie może być w przeszłości");
        }
        if (!deliveryDate.equals(order.getDeliveryDate())) {
            order.changeDeliveryDate(deliveryDate, users.getReferenceById(userId));
        }
        return deliOrderMapper.toResponse(order);
    }

    /**
     * Łączy niezrealizowane zamówienia jednego sklepu na ten sam dzień dostawy (np. dwa wysłane tego samego dnia)
     * w nowe zamówienie: te same produkty sumuje, dokumenty przypina do połączonego. Źródłowe zostają w bazie
     * i znikają z list, dopóki połączenia nie cofnie {@link #split}.
     */
    @Transactional
    public DeliOrderResponse merge(UUID storeId, List<UUID> orderIds, UUID userId) {
        Set<UUID> ids = new HashSet<>(orderIds);
        if (ids.size() < 2) {
            throw ApiException.badRequest("Wybierz co najmniej dwa zamówienia");
        }
        List<DeliOrder> sources = new ArrayList<>(orders.findAllById(ids));
        if (sources.size() != ids.size() || sources.stream().anyMatch(o -> !o.getFormStore().getId().equals(storeId))) {
            throw ApiException.notFound("Nie znaleziono zamówienia");
        }
        DeliOrder first = sources.getFirst();
        for (DeliOrder source : sources) {
            ensureActive(source);
            if (!source.getStatus().isOpen()) {
                throw ApiException.conflict("Zrealizowanego zamówienia nie można połączyć z innym");
            }
            if (!source.getStore().getId().equals(first.getStore().getId())
                    || !source.getDeliveryDate().equals(first.getDeliveryDate())) {
                throw ApiException.badRequest("Połączyć można tylko zamówienia jednego sklepu na ten sam dzień dostawy");
            }
        }
        sources.sort(Comparator.comparing(DeliOrder::getSubmittedAt).thenComparing(DeliOrder::getId));

        AppUser by = users.getReferenceById(userId);
        DeliOrder merged = DeliOrder.merged(sources, by);
        // Dział za działem i kategoria za kategorią w kolejności pierwszego wystąpienia; pozycje spoza formularza
        // zostają na końcu działu, a ten sam produkt w tej samej kategorii i jednostce jest jedną pozycją.
        Map<String, Map<String, Map<String, MergedLine>>> bySection = new LinkedHashMap<>();
        for (DeliOrder source : sources) {
            for (DeliOrderItem item : source.getItems()) {
                bySection.computeIfAbsent(item.getSectionName(), key -> new LinkedHashMap<>())
                        .computeIfAbsent(item.getCategoryName(), key -> new LinkedHashMap<>())
                        .computeIfAbsent(item.getProduct().getId() + ":" + item.getUnit(), key -> new MergedLine(item))
                        .add(item);
            }
        }
        bySection.forEach((section, categories) -> {
            List<String> names = new ArrayList<>(categories.keySet());
            if (names.remove(EXTRA_CATEGORY)) {
                names.add(EXTRA_CATEGORY);
            }
            for (String category : names) {
                for (MergedLine line : categories.get(category).values()) {
                    merged.addItem(line.item.getProduct(), section, category, line.item.getUnit(), line.ordered,
                            line.quantity);
                }
            }
        });
        orders.save(merged);
        for (DeliOrder source : sources) {
            source.getDocuments().forEach(link -> merged.linkDocument(link.getDocument(), by));
        }
        return deliOrderMapper.toResponse(orders.saveAndFlush(merged));
    }

    /** Suma pozycji jednego produktu z kilku łączonych zamówień. */
    private static final class MergedLine {
        private final DeliOrderItem item;
        private BigDecimal ordered = BigDecimal.ZERO;
        private BigDecimal quantity = BigDecimal.ZERO;

        MergedLine(DeliOrderItem item) {
            this.item = item;
        }

        void add(DeliOrderItem other) {
            ordered = ordered.add(other.getOrderedQuantity());
            quantity = quantity.add(other.getQuantity());
        }
    }

    /**
     * Cofa połączenie niezrealizowanego zamówienia: zamówienia źródłowe wracają na listy takie, jak przed połączeniem,
     * a połączone (z ewentualnymi późniejszymi poprawkami) zostaje w bazie oznaczone jako rozdzielone.
     */
    @Transactional
    public List<DeliOrderSummaryResponse> split(UUID storeId, UUID orderId, UUID userId) {
        DeliOrder order = loadIncoming(storeId, orderId);
        ensureActive(order);
        if (order.getMergedAt() == null) {
            throw ApiException.conflict("To zamówienie nie powstało z połączenia");
        }
        if (!order.getStatus().isOpen()) {
            throw ApiException.conflict("Zamówienie jest już zrealizowane - nie można cofnąć połączenia");
        }
        List<DeliOrder> sources = List.copyOf(order.getMergedFrom());
        order.split(users.getReferenceById(userId));
        return sources.stream().map(deliOrderMapper::toSummary).toList();
    }

    @Transactional
    public DeliOrderResponse correct(UUID storeId, UUID orderId, CorrectDeliOrderRequest request, UUID userId) {
        DeliOrder order = loadIncoming(storeId, orderId);
        ensureActive(order);
        if (!order.getStatus().isOpen()) {
            throw ApiException.conflict("Zamówienie jest już zrealizowane - nie można zmieniać ilości");
        }
        order.markRead(users.getReferenceById(userId));
        applyQuantities(order, request, userId);
        return deliOrderMapper.toResponse(order);
    }

    /** Zapisuje nowe ilości pozycji; gdy któraś się zmieniła, zamówienie jest oznaczone jako poprawione. */
    private void applyQuantities(DeliOrder order, CorrectDeliOrderRequest request, UUID userId) {
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
    }

    /**
     * Usuwa niezrealizowane zamówienie tylko po stronie tego sklepu: znika z jego listy, a druga strona widzi status
     * "usunięte". Nic nie jest kasowane - {@link #restore} przywraca zamówienie. Sklep, który je złożył,
     * wycofuje je z uprawnieniem Podgląd, dostawca usuwa z uprawnieniem Edycja (jak poprawki i realizację).
     * Zrealizowane usuwa tylko właściciel.
     */
    @Transactional
    public DeliOrderResponse delete(UUID storeId, UUID orderId, boolean canEdit, boolean owner, UUID userId) {
        DeliOrder order = loadOwnSide(storeId, orderId, canEdit);
        if (!order.getStatus().isOpen() && !owner) {
            throw ApiException.conflict("Zamówienie jest już zrealizowane - usunąć je może tylko właściciel");
        }
        if (order.getMergedInto() != null || order.getSplitAt() != null) {
            throw ApiException.conflict("Zamówienie jest połączone z innym - usuń połączone zamówienie");
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
                    "Przychodzące zamówienie może zmienić, usunąć albo przywrócić osoba z uprawnieniem do edycji");
        }
        return order;
    }

    /**
     * Usuniętego po którejkolwiek stronie zamówienia nie poprawiamy ani nie realizujemy; tak samo połączonego w inne
     * i rozdzielonego.
     */
    private static void ensureActive(DeliOrder order) {
        if (order.getMergedInto() != null) {
            throw ApiException.conflict("Zamówienie jest połączone z innym - zmieniaj połączone zamówienie");
        }
        if (order.getSplitAt() != null) {
            throw ApiException.conflict("Połączenie zostało cofnięte - zmieniaj zamówienia, z których powstało");
        }
        if (order.getStoreDeletedAt() != null) {
            throw ApiException.conflict("Sklep wycofał to zamówienie - nie można go poprawiać ani realizować");
        }
        if (order.getFormStoreDeletedAt() != null) {
            throw ApiException.conflict("Zamówienie jest usunięte - przywróć je, żeby je poprawiać albo realizować");
        }
    }

    @Transactional
    /** Realizacja; {@code request} (może być {@code null}) niesie zrealizowane ilości, zapisane w tej samej transakcji. */
    public DeliOrderResponse fulfill(UUID storeId, UUID orderId, CorrectDeliOrderRequest request, UUID userId) {
        DeliOrder order = loadIncoming(storeId, orderId);
        ensureActive(order);
        if (order.getStatus() == DeliOrderStatus.FULFILLED) {
            throw ApiException.conflict("Zamówienie jest już oznaczone jako zrealizowane");
        }
        if (request != null) {
            applyQuantities(order, request, userId);
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
        ensureActive(order);
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
