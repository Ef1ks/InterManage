package com.intermanage.backend.mea.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.SecretCipher;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.intermanage.backend.mea.dto.MeaItem;
import com.intermanage.backend.mea.dto.MeaListResponse;
import com.intermanage.backend.mea.dto.MeaSettingsRequest;
import com.intermanage.backend.mea.dto.MeaSettingsResponse;
import com.intermanage.backend.mea.dto.MeaTab;
import com.intermanage.backend.mea.entity.MeaApiCredentials;
import com.intermanage.backend.mea.repository.MeaApiCredentialsRepository;
import com.intermanage.backend.mea.entity.MeaOrder;
import com.intermanage.backend.mea.repository.MeaAthosImportRepository;
import com.intermanage.backend.mea.repository.MeaOrderRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

import tools.jackson.databind.JsonNode;

/**
 * Lista MEA z portalu Grupy Muszkieterów dla sklepu. Loguje się kluczem API sklepu (token trzymany w pamięci do
 * wygaśnięcia) i mapuje odpowiedź {@code /api/mea/} na pozycje z zakładką. Klucze API zapisuje i oddaje tylko jako
 * „zapisane/niezapisane". Sesję portalu ({@link #session}) daje też formularzom MEA.
 */
@Service
public class MeaService {

    /** Token odświeżamy, gdy zostało mu mniej niż tyle do wygaśnięcia. */
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);

    /** Zalogowany sklep: shopID do API i ważny token. */
    public record Session(int shopId, String token) {
    }

    private final MeaApiCredentialsRepository credentials;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final SecretCipher cipher;
    private final MurphysoftClient client;
    private final MeaAthosService athos;
    private final MeaOrderRepository orders;
    /**
     * Ile dni wstecz obejmuje lista MEA (okno {@code day} w API, filtruje po dacie dokumentu). Promocja trwająca dziś
     * bywa z dokumentem sprzed roku, a eMEA trzyma MEA lokalnie i akumuluje je, więc żeby „Trwa promocja" zgadzało się
     * z eMEA, pytamy o rok (na 10023 day=365 daje 72 trwające jak w eMEA). Tyle samo wstecz pobieramy formularze.
     */
    private final int listDays;
    /**
     * Ile najnowszych MEA pokazujemy, jak {@code MeaLimitWyswietlania} w eMEA. Starsze archiwum obcinamy, żeby liczby
     * zgadzały się z eMEA, które też trzyma tylko tyle (formularze w tle pobieramy dla wszystkich z listy).
     */
    private final int listLimit;

    private final Map<UUID, MurphysoftClient.Token> tokens = new ConcurrentHashMap<>();

    public MeaService(MeaApiCredentialsRepository credentials, StoreRepository stores, AppUserRepository users,
            SecretCipher cipher, MurphysoftClient client, MeaAthosService athos, MeaOrderRepository orders,
            @Value("${app.mea.list-days}") int listDays,
            @Value("${app.mea.list-limit}") int listLimit) {
        this.credentials = credentials;
        this.stores = stores;
        this.users = users;
        this.cipher = cipher;
        this.client = client;
        this.athos = athos;
        this.orders = orders;
        this.listDays = listDays;
        this.listLimit = listLimit;
    }

    @Transactional(readOnly = true)
    public MeaSettingsResponse settings(UUID storeId) {
        MeaApiCredentials saved = credentials.findByStoreId(storeId).orElse(null);
        Store store = stores.getReferenceById(storeId);
        Integer shopId = shopId(store, false);
        if (saved == null) {
            return new MeaSettingsResponse(false, false, shopId, null, null);
        }
        return new MeaSettingsResponse(true, saved.getApiSecret() != null, shopId, saved.getUpdatedAt(),
                saved.getUpdatedBy() == null ? null : saved.getUpdatedBy().getDisplayName());
    }

    @Transactional
    public MeaSettingsResponse save(UUID storeId, MeaSettingsRequest request, UUID userId) {
        String key = trimToNull(request.apiKey());
        String secret = trimToNull(request.apiSecret());
        MeaApiCredentials saved = credentials.findByStoreId(storeId)
                .orElseGet(() -> new MeaApiCredentials(stores.getReferenceById(storeId)));
        if (saved.getApiKey() == null && key == null) {
            throw ApiException.badRequest("Podaj klucz API (KEY) z portalu Grupy Muszkieterów");
        }
        saved.update(key == null ? saved.getApiKey() : cipher.encrypt(key),
                secret == null ? null : cipher.encrypt(secret), users.getReferenceById(userId));
        credentials.save(saved);
        tokens.remove(storeId);
        return settings(storeId);
    }

    /** Lista MEA sklepu do pokazania (obcięta do {@code listLimit}). */
    @Transactional(readOnly = true)
    public MeaListResponse list(UUID storeId) {
        MeaListResponse list = fullList(storeId);
        Map<String, MeaAthosImportRepository.LatestSend> sends = athos.latestSends(storeId);
        Map<String, MeaOrder> saved = orders.findLatestSaved(storeId).stream()
                .collect(Collectors.toMap(MeaOrder::getNumber, Function.identity(), (a, b) -> a));
        // Sklep bez odczytu dziennika ATHOS i bez zamówień z aplikacji liczy zakładki po staremu (flaga z portalu).
        if ((sends != null || !saved.isEmpty()) && list.configured()) {
            LocalDate today = LocalDate.now(StoreTime.ZONE);
            Map<String, MeaAthosImportRepository.LatestSend> athosSends = sends == null ? Map.of() : sends;
            list = new MeaListResponse(true, list.dateFrom(), list.items().stream()
                    .map(item -> withSends(item, athosSends.get(item.number()), saved.get(item.number()), today))
                    .toList());
        }
        return limited(list, listLimit);
    }

    /**
     * Zakładka z wysyłek, jak w eMEA: MEA wysłane (plik zapisany do ATHOS z aplikacji albo wysyłka w dzienniku
     * Histo.Log), a promocja jeszcze nie ruszyła = „Wysłane”; niewysłane z otwartą rezerwacją = „Do wysłania”;
     * niewysłane po rezerwacji = „Inne”. Trwające i zakończone promocje zostają w swoich zakładkach.
     */
    private static MeaItem withSends(MeaItem item, MeaAthosImportRepository.LatestSend send, MeaOrder order,
            LocalDate today) {
        MeaTab tab = item.tab();
        if (tab == MeaTab.TO_SEND || tab == MeaTab.SENT_NOT_STARTED) {
            if (send != null || order != null) {
                tab = MeaTab.SENT_NOT_STARTED;
            } else {
                tab = item.reservationTo() == null || !item.reservationTo().isBefore(today) ? MeaTab.TO_SEND
                        : MeaTab.OTHER;
            }
        }
        return new MeaItem(item.number(), item.name(), item.promoFrom(), item.promoTo(), item.reservationFrom(),
                item.reservationTo(), item.ordered(), tab, item.files(),
                send == null ? null : send.getSentAt(), send == null ? null : send.getPositions(),
                send == null ? null : send.getColis(),
                order == null ? null : order.getSavedAt(), order == null ? null : order.getColis());
    }

    /** Cała lista MEA sklepu z {@code listDays} dni (do pobierania formularzy w tle); bez klucza pusta. */
    @Transactional(readOnly = true)
    public MeaListResponse fullList(UUID storeId) {
        if (credentials.findByStoreId(storeId).isEmpty()) {
            return MeaListResponse.notConfigured();
        }
        Session session = session(storeId);
        return parse(client.meaList(session.shopId(), session.token(), listDays));
    }

    /** Sklepy z zapisanym kluczem API (do pobierania formularzy w tle). */
    @Transactional(readOnly = true)
    public List<UUID> configuredStores() {
        return credentials.findAll().stream().map(saved -> saved.getStore().getId()).toList();
    }

    /** Sesja portalu dla sklepu; bez klucza 409. */
    @Transactional(readOnly = true)
    public Session session(UUID storeId) {
        MeaApiCredentials saved = credentials.findByStoreId(storeId)
                .orElseThrow(() -> ApiException.conflict("Sklep nie ma zapisanego klucza API MEA"));
        int shopId = shopId(stores.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu")), true);
        MurphysoftClient.Token current = tokens.get(storeId);
        if (current == null || !Instant.now().isBefore(current.expiresAt().minus(TOKEN_MARGIN))) {
            current = client.authorize(shopId, cipher.decrypt(saved.getApiKey()));
            tokens.put(storeId, current);
        }
        return new Session(shopId, current.value());
    }

    private static MeaListResponse limited(MeaListResponse list, int limit) {
        if (list.items().size() <= limit) {
            return list;
        }
        return new MeaListResponse(list.configured(), list.dateFrom(), list.items().subList(0, limit));
    }

    private static MeaListResponse parse(JsonNode response) {
        LocalDate dateFrom = date(response.path("date_document_from").asString(null));
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        JsonNode mea = response.path("mea");
        // Limit (jak eMEA) trzyma najnowsze MEA, ale po dacie końca promocji malejąco, a nie po wewnętrznym id:
        // kilka trwających promocji to stare MEA z niskim id i obcięcie po id gubiłoby je. Tie-break po id.
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : mea.properties()) {
            entries.add(entry);
        }
        entries.sort(Comparator
                .comparing((Map.Entry<String, JsonNode> entry) -> endKey(entry.getValue()))
                .thenComparingLong(entry -> parseId(entry.getKey()))
                .reversed());
        List<MeaItem> items = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : entries) {
            JsonNode node = entry.getValue();
            LocalDate promoFrom = date(node.path("promocja_od").asString(null));
            LocalDate promoTo = date(node.path("promocja_do").asString(null));
            boolean ordered = "1".equals(node.path("zamowienie").asString(""));
            List<String> files = new ArrayList<>();
            for (JsonNode file : node.path("file")) {
                files.add(file.asString(""));
            }
            items.add(new MeaItem(
                    node.path("numer").asString(""),
                    node.path("tekst").asString("").trim(),
                    promoFrom,
                    promoTo,
                    date(node.path("rezerwacja_od").asString(null)),
                    date(node.path("rezerwacja_do").asString(null)),
                    ordered,
                    tab(promoFrom, promoTo, ordered, today),
                    files,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        return new MeaListResponse(true, dateFrom, items);
    }

    /** Data końca promocji jako klucz sortowania (ISO, więc porównywalny tekstowo); brak = najniższy. */
    private static String endKey(JsonNode node) {
        String value = node.path("promocja_do").asString("");
        return value == null || value.isBlank() ? "0000-00-00" : value;
    }

    private static long parseId(String key) {
        try {
            return Long.parseLong(key.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static MeaTab tab(LocalDate promoFrom, LocalDate promoTo, boolean ordered, LocalDate today) {
        if (promoFrom == null || promoTo == null) {
            return MeaTab.OTHER;
        }
        if (promoTo.isBefore(today)) {
            return MeaTab.ARCHIVE;
        }
        if (!promoFrom.isAfter(today)) {
            return MeaTab.ONGOING;
        }
        return ordered ? MeaTab.SENT_NOT_STARTED : MeaTab.TO_SEND;
    }

    private Integer shopId(Store store, boolean required) {
        String number = store.getNumber();
        try {
            return Integer.valueOf(Integer.parseInt(number.trim()));
        } catch (NumberFormatException | NullPointerException exception) {
            if (required) {
                throw ApiException.conflict("Sklep nie ma numeru punktu sprzedaży potrzebnego do połączenia z MEA");
            }
            return null;
        }
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
