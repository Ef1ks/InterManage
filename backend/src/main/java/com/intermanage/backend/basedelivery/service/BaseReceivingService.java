package com.intermanage.backend.basedelivery.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.basedelivery.component.BaseReceiveMacro;
import com.intermanage.backend.basedelivery.component.Itm8Macro;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryResponse;
import com.intermanage.backend.basedelivery.dto.BaseReceivingOrderRequest;
import com.intermanage.backend.basedelivery.dto.BaseReceivingResponse;
import com.intermanage.backend.basedelivery.entity.BaseDeliveryNote;
import com.intermanage.backend.basedelivery.entity.BaseDeliveryOrder;
import com.intermanage.backend.basedelivery.mapper.BaseDeliveryMapper;
import com.intermanage.backend.basedelivery.repository.BaseDeliveryNoteRepository;
import com.intermanage.backend.basedelivery.repository.BaseDeliveryOrderRepository;
import com.intermanage.backend.common.component.CompressedUploads;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.store.component.DefaultFolders;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.store.service.StoreFileLocationService;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Wprowadzenie bonu z bazy do Mercalys makrem: numery zamówień, na które weszły pliki bonu, i tymczasowe bony dostawy
 * (Excel) przysłane przez agenta, z których przeglądarka liczy różnice. Treść makr składa {@link BaseReceiveMacro}.
 */
@Service
public class BaseReceivingService {

    /** Pliki bonu sprawdzamy zawsze od obu dostawców bazy: alkohol bywa w dokumencie pod 538. */
    private static final Map<String, String> BASE_SUPPLIERS = new LinkedHashMap<>();

    static {
        BASE_SUPPLIERS.put("538", "SCAPR POLSKA SP.Z.O.O.");
        BASE_SUPPLIERS.put("88110", "SCAPR CNUF88110");
    }

    private final BaseDeliveryService deliveries;
    private final BaseDeliveryOrderRepository orders;
    private final BaseDeliveryNoteRepository notes;
    private final SupplierRepository suppliers;
    private final StoreFileLocationService locations;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final BaseDeliveryMapper mapper;

    public BaseReceivingService(BaseDeliveryService deliveries, BaseDeliveryOrderRepository orders,
            BaseDeliveryNoteRepository notes, SupplierRepository suppliers, StoreFileLocationService locations,
            StoreRepository stores, AppUserRepository users, BaseDeliveryMapper mapper) {
        this.deliveries = deliveries;
        this.orders = orders;
        this.notes = notes;
        this.suppliers = suppliers;
        this.locations = locations;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public BaseReceivingResponse get(UUID storeId, UUID deliveryId) {
        BaseDeliveryResponse delivery = deliveries.get(storeId, deliveryId);
        String bon = delivery.number();
        Map<String, BaseDeliveryOrder> activeOrders = orders
                .findByStoreIdAndBonNumberAndReplacedAtIsNullOrderBySupplierCode(storeId, bon).stream()
                .collect(Collectors.toMap(BaseDeliveryOrder::getSupplierCode, Function.identity()));
        Map<String, BaseDeliveryNote> activeNotes = notes.findByStoreIdAndBonNumberAndReplacedAtIsNull(storeId, bon)
                .stream().collect(Collectors.toMap(BaseDeliveryNote::getSupplierCode, Function.identity()));
        List<BaseReceivingResponse.Supplier> list = new ArrayList<>();
        for (BaseReceiveMacro.Supplier supplier : suppliers(storeId, delivery)) {
            BaseDeliveryOrder order = activeOrders.get(supplier.code());
            BaseDeliveryNote note = activeNotes.get(supplier.code());
            list.add(new BaseReceivingResponse.Supplier(supplier.code(), supplier.name(),
                    order == null ? null : mapper.toReceivingOrder(order),
                    note == null ? null : note(storeId, note)));
        }
        return new BaseReceivingResponse(bon, list);
    }

    private BaseReceivingResponse.Note note(UUID storeId, BaseDeliveryNote note) {
        BaseDeliveryService.NoteRead read = deliveries.tryReadTemporaryNote(storeId,
                new CompressedUploads.BytesFile("file", note.getFileName(), note.getContent()));
        return mapper.toReceivingNote(note, read.note(), read.error());
    }

    /** Numer zamówienia wpisany ręcznie (dostawę wprowadził człowiek albo makro zgubiło numer). */
    @Transactional
    public BaseReceivingResponse enterOrder(UUID storeId, UUID deliveryId, BaseReceivingOrderRequest request,
            UUID userId) {
        BaseDeliveryResponse delivery = deliveries.get(storeId, deliveryId);
        String code = Itm8Macro.key(request.supplierCode());
        if (suppliers(storeId, delivery).stream().noneMatch(supplier -> supplier.code().equals(code))) {
            throw ApiException.badRequest("Ten bon nie ma pliku dostawcy " + request.supplierCode());
        }
        saveOrder(storeId, delivery.number(), code, request.orderNumber().strip(), userId);
        return get(storeId, deliveryId);
    }

    /** Makro „Wprowadź dostawę”: pliki bonu z Obsługi BL, zamówienia i bony Excel. */
    @Transactional(readOnly = true)
    public String receiveMacro(UUID storeId, BaseDeliveryResponse delivery) {
        Map<String, String> known = new LinkedHashMap<>();
        openOrders(storeId, delivery.number()).forEach(order -> known.put(order.getSupplierCode(),
                order.getOrderNumber()));
        return BaseReceiveMacro.receive(delivery.number(), suppliers(storeId, delivery), known,
                LocalDate.now(StoreTime.ZONE), folder(storeId));
    }

    /** Makro „Zatwierdź”: Nr BL w nagłówkach i zakończenie dostaw bonu. */
    @Transactional(readOnly = true)
    public List<BaseReceiveMacro.Order> ordersToClose(UUID storeId, String bonNumber) {
        return openOrders(storeId, bonNumber).stream()
                .map(order -> new BaseReceiveMacro.Order(order.getSupplierCode(), order.getOrderNumber())).toList();
    }

    /** Zamówienie odczytane przez makro. */
    @Transactional
    public void orderRead(UUID storeId, String bonNumber, String supplierCode, String orderNumber) {
        saveOrder(storeId, bonNumber, Itm8Macro.key(supplierCode), orderNumber, null);
    }

    /** Dostawa zakończona przez makro. */
    @Transactional
    public void closed(UUID storeId, String bonNumber, String orderNumber) {
        openOrders(storeId, bonNumber).stream().filter(order -> order.getOrderNumber().equals(orderNumber))
                .forEach(BaseDeliveryOrder::close);
    }

    /** Tymczasowy bon dostawy zapisany przez makro (agent wysyła go po makrze, także spakowany gzipem). */
    @Transactional
    public void saveNote(UUID storeId, String bonNumber, String supplierCode, MultipartFile file) {
        MultipartFile unpacked = CompressedUploads.unpacked(file, BaseDeliveryService.MAX_FILE_BYTES);
        byte[] content;
        try {
            content = unpacked.getBytes();
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać bonu");
        }
        if (content.length == 0) {
            throw ApiException.badRequest("Bon jest pusty");
        }
        notes.findByStoreIdAndBonNumberAndSupplierCodeAndReplacedAtIsNull(storeId, bonNumber, supplierCode)
                .ifPresent(previous -> {
                    previous.replace();
                    notes.saveAndFlush(previous);
                });
        String name = unpacked.getOriginalFilename() == null ? "bon.xlsx" : unpacked.getOriginalFilename();
        notes.save(new BaseDeliveryNote(stores.getReferenceById(storeId), bonNumber, supplierCode,
                name.length() > 255 ? name.substring(name.length() - 255) : name, content));
    }

    private void saveOrder(UUID storeId, String bonNumber, String supplierCode, String orderNumber, UUID userId) {
        BaseDeliveryOrder current = orders.findByStoreIdAndBonNumberAndSupplierCodeAndReplacedAtIsNull(storeId,
                bonNumber, supplierCode).orElse(null);
        if (current != null) {
            if (current.getOrderNumber().equals(orderNumber)) {
                return;
            }
            current.replace();
            orders.saveAndFlush(current);
        }
        orders.save(new BaseDeliveryOrder(stores.getReferenceById(storeId), bonNumber, supplierCode, orderNumber,
                userId == null ? null : users.getReferenceById(userId)));
    }

    private List<BaseDeliveryOrder> openOrders(UUID storeId, String bonNumber) {
        return orders.findByStoreIdAndBonNumberAndReplacedAtIsNullOrderBySupplierCode(storeId, bonNumber).stream()
                .filter(order -> order.getClosedAt() == null).toList();
    }

    /** Dostawcy plików bonu z nazwą z listy dostawców sklepu. */
    private List<BaseReceiveMacro.Supplier> suppliers(UUID storeId, BaseDeliveryResponse delivery) {
        Set<String> codes = new LinkedHashSet<>(BASE_SUPPLIERS.keySet());
        delivery.lines().forEach(line -> {
            String code = Itm8Macro.key(line.supplierCode());
            if (!code.isEmpty()) {
                codes.add(code);
            }
        });
        Map<String, String> names = new LinkedHashMap<>();
        suppliers.findImported(storeId).forEach(supplier -> names.put(Itm8Macro.key(supplier.getCode()),
                supplier.getName()));
        return codes.stream().map(code -> new BaseReceiveMacro.Supplier(code,
                names.getOrDefault(code, BASE_SUPPLIERS.getOrDefault(code, code)))).toList();
    }

    private String folder(UUID storeId) {
        String folder = locations.get(storeId).locations().get("folder:" + DefaultFolders.BASE_NOTES);
        return folder == null || folder.isBlank() ? DefaultFolders.of(DefaultFolders.BASE_NOTES) : folder;
    }
}
