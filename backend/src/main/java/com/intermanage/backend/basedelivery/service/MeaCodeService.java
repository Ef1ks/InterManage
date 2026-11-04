package com.intermanage.backend.basedelivery.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.basedelivery.dto.MeaConfirmRequest;
import com.intermanage.backend.basedelivery.dto.MeaOverview;
import com.intermanage.backend.basedelivery.entity.MeaConfirmation;
import com.intermanage.backend.basedelivery.mapper.BaseDeliveryMapper;
import com.intermanage.backend.basedelivery.repository.BaseDeliveryRepository;
import com.intermanage.backend.basedelivery.repository.MeaConfirmationRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.leaflet.repository.PromoLeafletRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Kody MEA z dokumentów dostaw z bazy i z gazetek: do wpisania w Mercalys zostają tylko te, których jeszcze nie potwierdzono.
 * Kod trafia do wpisanych dopiero po potwierdzeniu; cofnięcie potwierdzenia (znacznik) przywraca jego kody na listę.
 */
@Service
public class MeaCodeService {

    private static final Comparator<MeaOverview.PendingCode> PENDING_ORDER =
            Comparator.comparing(MeaOverview.PendingCode::deliveryDate, Comparator.reverseOrder())
                    .thenComparing(MeaOverview.PendingCode::code);

    private final BaseDeliveryRepository deliveries;
    private final PromoLeafletRepository leaflets;
    private final MeaConfirmationRepository confirmations;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final BaseDeliveryMapper mapper;

    public MeaCodeService(BaseDeliveryRepository deliveries, PromoLeafletRepository leaflets,
            MeaConfirmationRepository confirmations, StoreRepository stores, AppUserRepository users,
            BaseDeliveryMapper mapper) {
        this.deliveries = deliveries;
        this.leaflets = leaflets;
        this.confirmations = confirmations;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    /** Kody do wpisania (z najnowszym bonem, w którym są) i ostatnie potwierdzenia. */
    @Transactional(readOnly = true)
    public MeaOverview overview(UUID storeId) {
        List<Occurrence> occurrences = new ArrayList<>();
        deliveries.findPendingMeaCodes(storeId).forEach(occurrence -> occurrences.add(new Occurrence(
                occurrence.getCode(), occurrence.getNumber(), occurrence.getDeliveryDate(), false)));
        leaflets.findPendingMeaCodes(storeId).forEach(occurrence -> occurrences.add(new Occurrence(
                occurrence.getCode(), occurrence.getWeek() + "/" + occurrence.getYear(), occurrence.getDateFrom(),
                true)));
        Map<String, List<Occurrence>> byCode = occurrences.stream().collect(Collectors.groupingBy(Occurrence::code));
        List<MeaOverview.PendingCode> pending = byCode.values().stream().map(found -> {
            Occurrence latest = found.stream().max(Comparator.comparing(Occurrence::date)).orElseThrow();
            long documentCount = found.stream().map(occurrence -> occurrence.leaflet() + occurrence.number())
                    .distinct().count();
            return new MeaOverview.PendingCode(latest.code(), latest.number(), latest.date(), documentCount,
                    latest.leaflet());
        }).sorted(PENDING_ORDER).toList();
        List<MeaOverview.Confirmation> history = confirmations.findTop20ByStoreIdOrderByConfirmedAtDesc(storeId)
                .stream().map(mapper::toConfirmation).toList();
        return new MeaOverview(pending, history);
    }

    /** Kod MEA w dokumencie z bazy (numer bonu) albo w gazetce ({@code leaflet}, numer „44/2026”). */
    private record Occurrence(String code, String number, LocalDate date, boolean leaflet) {
    }

    /** Potwierdza wpisanie kodów; kody już wpisane wcześniej pomija, a gdy nie zostaje żaden, nic nie zapisuje. */
    @Transactional
    public MeaOverview confirm(UUID storeId, MeaConfirmRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        Set<String> active = confirmations.findActiveCodes(storeId);
        Set<String> codes = request.codes().stream().map(String::strip).filter(code -> !active.contains(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!codes.isEmpty()) {
            MeaConfirmation confirmation = new MeaConfirmation(store, users.getReferenceById(userId));
            codes.forEach(confirmation::addCode);
            confirmations.saveAndFlush(confirmation);
        }
        return overview(storeId);
    }

    /** Cofnięcie potwierdzenia (znacznik): jego kody wracają do wpisania. */
    @Transactional
    public MeaOverview delete(UUID storeId, UUID confirmationId, UUID userId) {
        MeaConfirmation confirmation = confirmation(storeId, confirmationId);
        if (confirmation.getDeletedAt() == null) {
            AppUser by = users.getReferenceById(userId);
            confirmation.delete(by);
            confirmations.flush();
        }
        return overview(storeId);
    }

    /** Przywraca cofnięte potwierdzenie; kod potwierdzony w międzyczasie ponownie zostaje przy nowszym wpisie. */
    @Transactional
    public MeaOverview restore(UUID storeId, UUID confirmationId) {
        MeaConfirmation confirmation = confirmation(storeId, confirmationId);
        if (confirmation.getDeletedAt() != null) {
            confirmation.restore(confirmations.findActiveCodes(storeId));
            confirmations.flush();
        }
        return overview(storeId);
    }

    private MeaConfirmation confirmation(UUID storeId, UUID confirmationId) {
        return confirmations.findByIdAndStoreId(confirmationId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono potwierdzenia kodów MEA"));
    }
}
