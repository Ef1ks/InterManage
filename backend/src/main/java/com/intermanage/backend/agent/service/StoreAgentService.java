package com.intermanage.backend.agent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.agent.dto.AgentApi;
import com.intermanage.backend.agent.dto.AgentStatusResponse;
import com.intermanage.backend.agent.dto.StoreAgentResponse;
import com.intermanage.backend.agent.dto.StoreAgentsResponse;
import com.intermanage.backend.agent.entity.StoreAgent;
import com.intermanage.backend.agent.entity.StoreAgentSettings;
import com.intermanage.backend.agent.mapper.StoreAgentMapper;
import com.intermanage.backend.agent.repository.StoreAgentRepository;
import com.intermanage.backend.agent.repository.StoreAgentSettingsRepository;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.store.dto.ProtoSettingsResponse;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.store.service.StoreFileLocationService;
import com.intermanage.backend.store.service.StoreProtoSettingsService;

/**
 * Agent sklepu: klucze (pokazywane raz, w bazie SHA-256, jak czytniki kart), wybór sklepu „praca z folderami przez
 * agenta / przez przeglądarkę”, stan online i konfiguracja, którą agent dostaje przy starcie ({@link #hello}).
 *
 * <p>Foldery zapisu to ścieżki sklepu ({@code folder:<klucz>} w {@code store_file_locations}), folder plików PROTO
 * i plików nowych artykułów z ustawień plików PROTO oraz znane domyślne ścieżki. Pilnowane foldery: pliki cen z kasy
 * (TRACEART, z podfolderem BACKUP), zmiany cen CTRLPRIX w folderze plików PROTO i dziennik ATHOS.
 */
@Service
public class StoreAgentService {

    /** Agent jest online, gdy kontakt był w tym czasie (utrzymuje go otwarte czekanie na pliki). */
    public static final Duration ONLINE_WINDOW = Duration.ofSeconds(60);
    public static final int LONG_POLL_SECONDS = 25;

    public static final String TRACEART = "TRACEART";
    public static final String CTRLPRIX = "CTRLPRIX";
    public static final String ATHOS = "ATHOS";

    private static final String PROTO_FOLDER_KEY = "deli-order-proto";
    private static final String PRODUCT_FILE_KEY = "data-entry-product-file";
    private static final String FOLDER_PREFIX = "folder:";
    /** Domyślne ścieżki z frontu (gdy sklep nie ustawił własnej). */
    private static final Map<String, String> DEFAULT_PATHS = Map.of("leaflet-pro", "N:\\ALOA\\PRO\\IMPORT");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Agent po kluczu: kto i w którym sklepie. */
    public record AgentContext(UUID agentId, UUID storeId, String storeNumber, String storeName, String agentName) {
    }

    private final StoreAgentRepository agents;
    private final StoreAgentSettingsRepository settings;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final StoreFileLocationService locations;
    private final StoreProtoSettingsService protoSettings;
    private final StoreAgentMapper mapper;
    private final LiveEvents live;

    public StoreAgentService(StoreAgentRepository agents, StoreAgentSettingsRepository settings,
            StoreRepository stores, AppUserRepository users, StoreFileLocationService locations,
            StoreProtoSettingsService protoSettings, StoreAgentMapper mapper, LiveEvents live) {
        this.agents = agents;
        this.settings = settings;
        this.stores = stores;
        this.users = users;
        this.locations = locations;
        this.protoSettings = protoSettings;
        this.mapper = mapper;
        this.live = live;
    }

    @Transactional(readOnly = true)
    public StoreAgentsResponse list(UUID storeId) {
        Instant now = Instant.now();
        return new StoreAgentsResponse(enabled(storeId), agents.findByStore(storeId).stream()
                .map(agent -> mapper.toResponse(agent, online(agent, now), null)).toList());
    }

    @Transactional
    public StoreAgentsResponse add(UUID storeId, String name, UUID actorId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw ApiException.badRequest("Podaj nazwę agenta (do 100 znaków), np. „Komputer biuro”");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String key = "ima_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StoreAgent agent = agents.saveAndFlush(new StoreAgent(store, trimmed, hash(key),
                users.getReferenceById(actorId)));
        StoreAgentsResponse current = list(storeId);
        return new StoreAgentsResponse(current.enabled(), current.agents().stream()
                .map(item -> item.id().equals(agent.getId()) ? withKey(item, key) : item).toList());
    }

    @Transactional
    public StoreAgentsResponse remove(UUID storeId, UUID agentId, UUID actorId) {
        StoreAgent agent = agents.findById(agentId)
                .filter(found -> found.getDeletedAt() == null && found.getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono agenta"));
        agent.markDeleted(users.getReferenceById(actorId));
        live.publish(storeId, "agent", Map.of("action", "removed"));
        return list(storeId);
    }

    @Transactional
    public StoreAgentsResponse restore(UUID storeId, UUID agentId) {
        StoreAgent agent = agents.findById(agentId)
                .filter(found -> found.getDeletedAt() != null && found.getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono agenta"));
        agent.restore();
        live.publish(storeId, "agent", Map.of("action", "restored"));
        return list(storeId);
    }

    /** Wybór sklepu: pliki i foldery przez agenta ({@code true}) albo jak dotąd przez przeglądarkę. */
    @Transactional
    public StoreAgentsResponse setEnabled(UUID storeId, boolean enabled, UUID actorId) {
        StoreAgentSettings value = settings.findById(storeId).orElseGet(() -> new StoreAgentSettings(storeId));
        value.update(enabled, users.getReferenceById(actorId));
        settings.save(value);
        live.publish(storeId, "agent", Map.of("action", "settings", "enabled", enabled));
        return list(storeId);
    }

    @Transactional(readOnly = true)
    public boolean enabled(UUID storeId) {
        return settings.findById(storeId).map(StoreAgentSettings::isEnabled).orElse(false);
    }

    /** Stan agenta sklepu dla przeglądarki (najświeższy kontakt wśród aktywnych agentów). */
    @Transactional(readOnly = true)
    public AgentStatusResponse status(UUID storeId) {
        boolean enabled = enabled(storeId);
        Instant now = Instant.now();
        StoreAgent best = agents.findByStore(storeId).stream()
                .filter(agent -> agent.getDeletedAt() == null)
                .max((a, b) -> compare(a.getLastSeenAt(), b.getLastSeenAt()))
                .orElse(null);
        if (best == null) {
            return new AgentStatusResponse(enabled, false, false, false, null, null, null, null, List.of(),
                    List.of());
        }
        boolean online = online(best, now);
        return new AgentStatusResponse(enabled, true, online, enabled && online, best.getName(),
                best.getLastVersion(), best.getMachineName(), best.getLastSeenAt(),
                List.copyOf(folders(storeId).keySet()), watches(storeId).stream().map(AgentApi.Watch::kind).toList());
    }

    /** Agent po kluczu z nagłówka; każde żądanie odświeża kontakt. Nieznany albo usunięty klucz = 403. */
    @Transactional
    public AgentContext authenticate(String key) {
        StoreAgent agent = (key == null || key.isBlank() ? java.util.Optional.<StoreAgent>empty()
                : agents.findByKeyHash(hash(key.strip())))
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.forbidden("Nieznany klucz agenta sklepu"));
        agents.touch(agent.getId());
        Store store = agent.getStore();
        return new AgentContext(agent.getId(), store.getId(), store.getNumber(), store.getName(), agent.getName());
    }

    @Transactional
    public AgentApi.HelloResponse hello(AgentContext agent, AgentApi.Hello request) {
        agents.hello(agent.agentId(), cut(request.version(), 50), cut(request.machineName(), 100),
                cut(request.user(), 100));
        return new AgentApi.HelloResponse(agent.storeId(), agent.storeNumber(), agent.storeName(), agent.agentName(),
                enabled(agent.storeId()), folders(agent.storeId()), watches(agent.storeId()), LONG_POLL_SECONDS);
    }

    /** Foldery zapisu sklepu: klucz folderu przeglądarki → ścieżka na dysku. */
    @Transactional(readOnly = true)
    public Map<String, String> folders(UUID storeId) {
        Map<String, String> folders = new LinkedHashMap<>(DEFAULT_PATHS);
        ProtoSettingsResponse proto = protoSettings.get(storeId);
        folders.put(PROTO_FOLDER_KEY, proto.folderPath());
        if (proto.productFileFolderPath() != null && !proto.productFileFolderPath().isBlank()) {
            folders.put(PRODUCT_FILE_KEY, proto.productFileFolderPath());
        }
        locations.get(storeId).locations().forEach((key, value) -> {
            if (key.startsWith(FOLDER_PREFIX) && value != null && !value.isBlank() && looksLikeFolder(value)) {
                folders.put(key.substring(FOLDER_PREFIX.length()), value.strip());
            }
        });
        // Foldery tylko do odczytu (pilnowane), nie do zapisu.
        folders.remove("price-files");
        folders.remove("price-files:" + storeId);
        folders.remove("mea-athos:" + storeId);
        return folders;
    }

    /** Foldery do pilnowania. */
    @Transactional(readOnly = true)
    public List<AgentApi.Watch> watches(UUID storeId) {
        Map<String, String> values = locations.get(storeId).locations();
        String proto = protoSettings.get(storeId).folderPath();
        List<AgentApi.Watch> watches = new ArrayList<>();
        // Folder cen z kasy: ścieżka sklepu z ustawień (front zapisuje ją pod `folder:price-files`), bez niej folder PROTO.
        String traceart = firstFolder(values.get(FOLDER_PREFIX + "price-files"),
                values.get(FOLDER_PREFIX + "price-files:" + storeId), proto);
        watches.add(new AgentApi.Watch(TRACEART, traceart, true, "^TRACEART_\\d{8}_\\d{6}(_.*)?$"));
        watches.add(new AgentApi.Watch(CTRLPRIX, proto, true, "^CTRLPRIX_.+\\.traite\\d+$"));
        String athos = values.get(FOLDER_PREFIX + "mea-athos:" + storeId);
        if (athos != null && looksLikeFolder(athos)) {
            watches.add(new AgentApi.Watch(ATHOS, athos.strip(), false, "^Histo\\.Log$"));
        }
        return watches;
    }

    private static String firstFolder(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && looksLikeFolder(value)) {
                return value.strip();
            }
        }
        return values[values.length - 1];
    }

    public static boolean online(StoreAgent agent, Instant now) {
        return agent.getDeletedAt() == null && agent.getLastSeenAt() != null
                && agent.getLastSeenAt().isAfter(now.minus(ONLINE_WINDOW));
    }

    /** Ścieżka folderu (dysk z literą albo ścieżka sieciowa), a nie podpowiedź z wielokropkiem. */
    private static boolean looksLikeFolder(String value) {
        String path = value.strip();
        return !path.contains("…") && (path.matches("^[A-Za-z]:\\\\.*") || path.startsWith("\\\\"));
    }

    private static int compare(Instant a, Instant b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        return b == null ? 1 : a.compareTo(b);
    }

    private static StoreAgentResponse withKey(StoreAgentResponse item, String key) {
        return new StoreAgentResponse(item.id(), item.name(), item.createdAt(), item.createdBy(), item.lastSeenAt(),
                item.lastVersion(), item.machineName(), item.machineUser(), item.online(), item.deletedAt(), key);
    }

    private static String cut(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
