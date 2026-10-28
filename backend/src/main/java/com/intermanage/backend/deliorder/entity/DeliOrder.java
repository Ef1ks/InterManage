package com.intermanage.backend.deliorder.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** Zamówienie garmażu wysłane przez sklep ({@link #store}) do dostawcy, czyli właściciela formularza. */
@Entity
@Table(name = "deli_orders")
public class DeliOrder {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_store_id", nullable = false, updatable = false)
    private Store formStore;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliOrderStatus status = DeliOrderStatus.NEW;

    /** Obowiązująca data dostawy; zmieniona po złożeniu różni się od {@link #orderedDeliveryDate}. */
    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(name = "ordered_delivery_date", nullable = false, updatable = false)
    private LocalDate orderedDeliveryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_date_changed_by")
    private AppUser deliveryDateChangedBy;

    @Column(name = "delivery_date_changed_at")
    private Instant deliveryDateChangedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by", updatable = false)
    private AppUser submittedBy;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    /** Pierwsze otwarcie u dostawcy (status {@link DeliOrderStatus#READ}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "read_by")
    private AppUser readBy;

    @Column(name = "read_at")
    private Instant readAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corrected_by")
    private AppUser correctedBy;

    @Column(name = "corrected_at")
    private Instant correctedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fulfilled_by")
    private AppUser fulfilledBy;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    /** Zamawiający usunął (wycofał) zamówienie u siebie. Dane zostają, druga strona widzi status "usunięte". */
    @Column(name = "store_deleted_at")
    private Instant storeDeletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_deleted_by")
    private AppUser storeDeletedBy;

    /** Dostawca usunął zamówienie u siebie. */
    @Column(name = "form_store_deleted_at")
    private Instant formStoreDeletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_store_deleted_by")
    private AppUser formStoreDeletedBy;

    /** Zamówienie powstało z połączenia zamówień {@link #mergedFrom}. */
    @Column(name = "merged_at")
    private Instant mergedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_by")
    private AppUser mergedBy;

    /** Zamówienie weszło do połączonego; dopóki połączenie trwa, nie ma go na listach. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_into_id")
    private DeliOrder mergedInto;

    @OneToMany(mappedBy = "mergedInto")
    @OrderBy("submittedAt, id")
    private List<DeliOrder> mergedFrom = new ArrayList<>();

    /** Połączenie cofnięte: to zamówienie znika z list, źródłowe wracają. */
    @Column(name = "split_at")
    private Instant splitAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "split_by")
    private AppUser splitBy;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position, id")
    private List<DeliOrderItem> items = new ArrayList<>();

    /** Przypięte dokumenty ruchu międzymagazynowego; odpięte zostają w bazie, ale tu ich nie ma. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @SQLRestriction("unlinked_at is null")
    @OrderBy("linkedAt, id")
    private List<DeliOrderDocument> documents = new ArrayList<>();

    protected DeliOrder() {
    }

    public DeliOrder(Store formStore, Store store, LocalDate deliveryDate, AppUser submittedBy) {
        this.formStore = formStore;
        this.store = store;
        this.deliveryDate = deliveryDate;
        this.orderedDeliveryDate = deliveryDate;
        this.submittedBy = submittedBy;
        this.submittedAt = Instant.now();
    }

    /**
     * Puste zamówienie, do którego wejdą {@code sources} (ten sam sklep, dostawca i dzień, od najwcześniej złożonego).
     * Złożone jest jak pierwsze z nich, a odczytane przez osobę, która łączy.
     */
    public static DeliOrder merged(List<DeliOrder> sources, AppUser by) {
        DeliOrder first = sources.getFirst();
        DeliOrder order = new DeliOrder(first.formStore, first.store, first.deliveryDate, first.submittedBy);
        order.submittedAt = first.submittedAt;
        order.status = DeliOrderStatus.READ;
        order.readBy = by;
        order.readAt = Instant.now();
        order.mergedBy = by;
        order.mergedAt = order.readAt;
        for (DeliOrder source : sources) {
            source.mergedInto = order;
            order.mergedFrom.add(source);
            if (source.correctedAt != null && (order.correctedAt == null || source.correctedAt.isAfter(order.correctedAt))) {
                order.correctedAt = source.correctedAt;
                order.correctedBy = source.correctedBy;
            }
        }
        return order;
    }

    /** Cofa połączenie: zamówienia źródłowe wracają na listy, a to znika. */
    public void split(AppUser by) {
        splitAt = Instant.now();
        splitBy = by;
        mergedFrom.forEach(source -> source.mergedInto = null);
    }

    public void addItem(Product product, String sectionName, String categoryName, OrderUnit unit,
            BigDecimal quantity) {
        addItem(product, sectionName, categoryName, unit, quantity, quantity);
    }

    public void addItem(Product product, String sectionName, String categoryName, OrderUnit unit,
            BigDecimal orderedQuantity, BigDecimal quantity) {
        items.add(new DeliOrderItem(this, product, sectionName, categoryName, unit, orderedQuantity, quantity,
                items.size()));
    }

    /** Pierwsze otwarcie u dostawcy zmienia status z nowego na odczytane. */
    public void markRead(AppUser by) {
        if (status == DeliOrderStatus.NEW) {
            status = DeliOrderStatus.READ;
            readBy = by;
            readAt = Instant.now();
        }
    }

    public void changeDeliveryDate(LocalDate date, AppUser by) {
        deliveryDate = date;
        deliveryDateChangedBy = by;
        deliveryDateChangedAt = Instant.now();
    }

    public void markCorrected(AppUser by) {
        correctedBy = by;
        correctedAt = Instant.now();
    }

    public void markFulfilled(AppUser by) {
        markRead(by);
        status = DeliOrderStatus.FULFILLED;
        fulfilledBy = by;
        fulfilledAt = Instant.now();
    }

    public void markDeletedByStore(AppUser by) {
        storeDeletedAt = Instant.now();
        storeDeletedBy = by;
    }

    public void markDeletedByFormStore(AppUser by) {
        formStoreDeletedAt = Instant.now();
        formStoreDeletedBy = by;
    }

    public void restoreForStore() {
        storeDeletedAt = null;
        storeDeletedBy = null;
    }

    public void restoreForFormStore() {
        formStoreDeletedAt = null;
        formStoreDeletedBy = null;
    }

    public LocalDate getOrderedDeliveryDate() {
        return orderedDeliveryDate;
    }

    public AppUser getDeliveryDateChangedBy() {
        return deliveryDateChangedBy;
    }

    public Instant getDeliveryDateChangedAt() {
        return deliveryDateChangedAt;
    }

    public AppUser getReadBy() {
        return readBy;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getMergedAt() {
        return mergedAt;
    }

    public AppUser getMergedBy() {
        return mergedBy;
    }

    public DeliOrder getMergedInto() {
        return mergedInto;
    }

    public List<DeliOrder> getMergedFrom() {
        return mergedFrom;
    }

    public Instant getSplitAt() {
        return splitAt;
    }

    public AppUser getSplitBy() {
        return splitBy;
    }

    public Instant getStoreDeletedAt() {
        return storeDeletedAt;
    }

    public AppUser getStoreDeletedBy() {
        return storeDeletedBy;
    }

    public Instant getFormStoreDeletedAt() {
        return formStoreDeletedAt;
    }

    public AppUser getFormStoreDeletedBy() {
        return formStoreDeletedBy;
    }

    public UUID getId() {
        return id;
    }

    public Store getFormStore() {
        return formStore;
    }

    public Store getStore() {
        return store;
    }

    public DeliOrderStatus getStatus() {
        return status;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public AppUser getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public AppUser getCorrectedBy() {
        return correctedBy;
    }

    public Instant getCorrectedAt() {
        return correctedAt;
    }

    public AppUser getFulfilledBy() {
        return fulfilledBy;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    /** Przypina dokument ruchu międzymagazynowego; {@code null}, gdy już jest przypięty. */
    public DeliOrderDocument linkDocument(Document document, AppUser by) {
        if (documents.stream().anyMatch(link -> link.getDocument().getId().equals(document.getId()))) {
            return null;
        }
        DeliOrderDocument link = new DeliOrderDocument(this, document, by);
        documents.add(link);
        return link;
    }

    /** Odpina dokument: wpis zostaje w bazie z datą i osobą, która odpięła. {@code false}, gdy nie był przypięty. */
    public boolean unlinkDocument(UUID documentId, AppUser by) {
        DeliOrderDocument link = documents.stream().filter(l -> l.getDocument().getId().equals(documentId))
                .findFirst().orElse(null);
        if (link == null) {
            return false;
        }
        link.unlink(by);
        documents.remove(link);
        return true;
    }

    public List<DeliOrderDocument> getDocuments() {
        return documents;
    }

    public List<DeliOrderItem> getItems() {
        return items;
    }
}
