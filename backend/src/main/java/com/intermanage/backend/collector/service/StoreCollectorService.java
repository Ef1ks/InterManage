package com.intermanage.backend.collector.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.collector.component.CollectorProgram;
import com.intermanage.backend.collector.dto.StoreCollectorResponse;
import com.intermanage.backend.collector.entity.StoreCollector;
import com.intermanage.backend.collector.mapper.StoreCollectorMapper;
import com.intermanage.backend.collector.repository.StoreCollectorRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Kolektory sklepu: dodanie daje jednorazowy kod z cyfr (klawiatura kolektora jest numeryczna), kolektor wymienia go
 * na swój klucz. Nowy kod dla istniejącego kolektora służy po wyczyszczeniu urządzenia; stary klucz przestaje działać
 * dopiero przy parowaniu.
 */
@Service
public class StoreCollectorService {

    static final Duration PAIR_CODE_TTL = Duration.ofMinutes(15);
    private static final int PAIR_CODE_DIGITS = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Kolektor po kluczu: kto i w którym sklepie. */
    public record CollectorContext(UUID collectorId, UUID storeId, String storeNumber, String storeName, String name) {
    }

    /** Wynik parowania: klucz wraca tylko ten jeden raz. */
    public record Pairing(String key, CollectorContext collector) {
    }

    private final StoreCollectorRepository collectors;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final StoreCollectorMapper mapper;
    private final CollectorCatalogService catalog;
    private final CollectorProgram program;

    public StoreCollectorService(StoreCollectorRepository collectors, StoreRepository stores, AppUserRepository users,
            StoreCollectorMapper mapper, CollectorCatalogService catalog, CollectorProgram program) {
        this.program = program;
        this.collectors = collectors;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<StoreCollectorResponse> list(UUID storeId) {
        return list(storeId, null, null);
    }

    private List<StoreCollectorResponse> list(UUID storeId, UUID codeOf, String code) {
        String version = catalog.version(storeId);
        String latest = program.version().orElse(null);
        return collectors.findByStore(storeId).stream()
                .map(item -> mapper.toResponse(item, item.getId().equals(codeOf) ? code : null,
                        version.equals(item.getCatalogVersion()),
                        item.getLastVersion() == null || item.getLastVersion().equals(latest) ? null : latest))
                .toList();
    }

    @Transactional
    public List<StoreCollectorResponse> add(UUID storeId, String name, UUID actorId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw ApiException.badRequest("Podaj nazwę kolektora (do 100 znaków), np. „Kolektor 1”");
        }
        StoreCollector collector = new StoreCollector(store, trimmed, users.getReferenceById(actorId));
        String code = offer(collector);
        collectors.saveAndFlush(collector);
        return list(storeId, collector.getId(), code);
    }

    /** Nowy kod parowania (pierwszy wygasł albo urządzenie zostało wyczyszczone). */
    @Transactional
    public List<StoreCollectorResponse> newCode(UUID storeId, UUID collectorId) {
        StoreCollector collector = find(storeId, collectorId, false);
        String code = offer(collector);
        collectors.saveAndFlush(collector);
        return list(storeId, collectorId, code);
    }

    @Transactional
    public List<StoreCollectorResponse> remove(UUID storeId, UUID collectorId, UUID actorId) {
        find(storeId, collectorId, false).markDeleted(users.getReferenceById(actorId));
        collectors.flush();
        return list(storeId);
    }

    @Transactional
    public List<StoreCollectorResponse> restore(UUID storeId, UUID collectorId) {
        find(storeId, collectorId, true).restore();
        collectors.flush();
        return list(storeId);
    }

    @Transactional
    public Pairing pair(String code, String device, String version) {
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        StoreCollector collector = (digits.length() == PAIR_CODE_DIGITS
                ? collectors.findByPairCodeForUpdate(hash(digits)) : java.util.Optional.<StoreCollector>empty())
                .orElseThrow(() -> ApiException.forbidden(
                        "Kod jest zły albo wygasł. Nowy kod jest w Ustawieniach sklepu, Kolektory."));
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String key = "imk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        collector.pair(hash(key), cut(device, 100));
        collectors.saveAndFlush(collector);
        collectors.touch(collector.getId(), cut(version, 50));
        return new Pairing(key, context(collector));
    }

    /** Kolektor po kluczu; każde żądanie odświeża jego ostatni kontakt. Usunięty albo nieznany klucz = 403. */
    @Transactional
    public CollectorContext authenticate(String key, String version) {
        StoreCollector collector = (key == null || key.isBlank() ? java.util.Optional.<StoreCollector>empty()
                : collectors.findByKeyHash(hash(key.strip())))
                .orElseThrow(() -> ApiException.forbidden("Kolektor nie jest sparowany ze sklepem"));
        collectors.touch(collector.getId(), cut(version, 50));
        return context(collector);
    }

    /** Kolektor zapisał u siebie kartotekę tej wersji. */
    @Transactional
    public void synced(CollectorContext collector, String version) {
        if (version == null || !version.matches("[0-9a-f]{1,64}")) {
            throw ApiException.badRequest("Zła wersja danych");
        }
        collectors.synced(collector.collectorId(), version);
    }

    private StoreCollector find(UUID storeId, UUID collectorId, boolean deleted) {
        return collectors.findById(collectorId)
                .filter(item -> item.getStore().getId().equals(storeId) && (item.getDeletedAt() != null) == deleted)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono kolektora"));
    }

    private static String offer(StoreCollector collector) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < PAIR_CODE_DIGITS; i++) {
            code.append(RANDOM.nextInt(10));
        }
        collector.offerPairing(hash(code.toString()), Instant.now().plus(PAIR_CODE_TTL));
        return code.toString();
    }

    private static CollectorContext context(StoreCollector collector) {
        Store store = collector.getStore();
        return new CollectorContext(collector.getId(), store.getId(), store.getNumber(), store.getName(),
                collector.getName());
    }

    private static String cut(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
