package com.intermanage.backend.collector.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.collector.dto.StorePrinterRequest;
import com.intermanage.backend.collector.dto.StorePrinterResponse;
import com.intermanage.backend.collector.entity.StorePrinter;
import com.intermanage.backend.collector.mapper.StorePrinterMapper;
import com.intermanage.backend.collector.repository.StorePrinterRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Drukarki Bluetooth sklepu. Adres MAC wpisuje się raz w Intermanage, a kolektory dostają listę aktywnych drukarek na
 * podstawce; wersja listy (skrót) mówi kolektorowi, czy ma ją pobrać od nowa.
 */
@Service
public class StorePrinterService {

    /** Drukarka na kolektorze: to samo, co widzi strona, bez danych osób. */
    public record CollectorPrinter(String name, String mac, String model, String pin) {
    }

    private static final UUID NONE = new UUID(0, 0);

    private final StorePrinterRepository printers;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final StorePrinterMapper mapper;

    public StorePrinterService(StorePrinterRepository printers, StoreRepository stores, AppUserRepository users,
            StorePrinterMapper mapper) {
        this.printers = printers;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<StorePrinterResponse> list(UUID storeId) {
        return printers.findByStore(storeId).stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public List<StorePrinterResponse> add(UUID storeId, StorePrinterRequest request, UUID actorId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        Fields fields = fields(request);
        if (printers.macTaken(storeId, fields.mac(), NONE)) {
            throw ApiException.conflict("Drukarka z tym adresem jest już na liście");
        }
        printers.saveAndFlush(new StorePrinter(store, fields.name(), fields.mac(), fields.model(), fields.pin(),
                users.getReferenceById(actorId)));
        return list(storeId);
    }

    @Transactional
    public List<StorePrinterResponse> change(UUID storeId, UUID printerId, StorePrinterRequest request,
            UUID actorId) {
        StorePrinter printer = find(storeId, printerId, false);
        Fields fields = fields(request);
        if (printers.macTaken(storeId, fields.mac(), printerId)) {
            throw ApiException.conflict("Drukarka z tym adresem jest już na liście");
        }
        printer.change(fields.name(), fields.mac(), fields.model(), fields.pin(), users.getReferenceById(actorId));
        printers.flush();
        return list(storeId);
    }

    @Transactional
    public List<StorePrinterResponse> remove(UUID storeId, UUID printerId, UUID actorId) {
        find(storeId, printerId, false).markDeleted(users.getReferenceById(actorId));
        printers.flush();
        return list(storeId);
    }

    @Transactional
    public List<StorePrinterResponse> restore(UUID storeId, UUID printerId) {
        StorePrinter printer = find(storeId, printerId, true);
        if (printers.macTaken(storeId, printer.getMac(), printerId)) {
            throw ApiException.conflict("Drukarka z tym adresem jest już na liście");
        }
        printer.restore();
        printers.flush();
        return list(storeId);
    }

    /** Aktywne drukarki sklepu dla kolektora. */
    @Transactional(readOnly = true)
    public List<CollectorPrinter> forCollector(UUID storeId) {
        return printers.findByStore(storeId).stream()
                .filter(printer -> printer.getDeletedAt() == null)
                .map(printer -> new CollectorPrinter(printer.getName(), printer.getMac(),
                        printer.getModel() == null ? "" : printer.getModel(),
                        printer.getPin() == null ? "" : printer.getPin()))
                .toList();
    }

    /** Skrót listy dla kolektora: zmienia się z każdą zmianą drukarek sklepu. */
    @Transactional(readOnly = true)
    public String version(UUID storeId) {
        return version(forCollector(storeId));
    }

    public static String version(List<CollectorPrinter> list) {
        StringBuilder text = new StringBuilder();
        for (CollectorPrinter printer : list) {
            text.append(printer.mac()).append('|').append(printer.name()).append('|').append(printer.model())
                    .append('|').append(printer.pin()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Adres MAC bez dwukropków, kresek i spacji, wielkimi literami; inny = {@code null}. */
    public static String normalizeMac(String value) {
        if (value == null) {
            return null;
        }
        String hex = value.replaceAll("[\\s:.\\-]", "").toUpperCase(Locale.ROOT);
        return hex.matches("[0-9A-F]{12}") && !hex.equals("000000000000") ? hex : null;
    }

    private record Fields(String name, String mac, String model, String pin) {
    }

    private static Fields fields(StorePrinterRequest request) {
        if (request == null) {
            throw ApiException.badRequest("Podaj nazwę i adres drukarki");
        }
        String name = trimmed(request.name());
        if (name == null || name.length() > 60) {
            throw ApiException.badRequest("Podaj nazwę drukarki (do 60 znaków), np. „Garmaż 1”");
        }
        String mac = normalizeMac(request.mac());
        if (mac == null) {
            throw ApiException.badRequest("Adres Bluetooth ma 12 znaków 0-9 i A-F, np. AC:3F:A4:12:34:56");
        }
        String model = trimmed(request.model());
        if (model != null && model.length() > 60) {
            throw ApiException.badRequest("Model ma najwyżej 60 znaków");
        }
        String pin = trimmed(request.pin());
        if (pin != null && !pin.matches("[0-9A-Za-z]{1,16}")) {
            throw ApiException.badRequest("PIN to do 16 cyfr albo liter");
        }
        return new Fields(name, mac, model, pin);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String text = value.replaceAll("[\\r\\n|=]+", " ").strip();
        return text.isEmpty() ? null : text;
    }

    private StorePrinter find(UUID storeId, UUID printerId, boolean deleted) {
        return printers.findById(printerId)
                .filter(item -> item.getStore().getId().equals(storeId) && (item.getDeletedAt() != null) == deleted)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono drukarki"));
    }
}
