package com.intermanage.backend.sales.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse;
import com.intermanage.backend.sales.dto.ProfitTargetProgressResponse;
import com.intermanage.backend.sales.dto.ProfitTargetRequest;
import com.intermanage.backend.sales.dto.ProfitTargetResponse;
import com.intermanage.backend.sales.entity.ProfitTarget;
import com.intermanage.backend.sales.mapper.ProfitTargetMapper;
import com.intermanage.backend.sales.repository.ProfitTargetRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Roczne cele zysku sklepu (ustawienia podsumowania sprzedaży i strat) i postęp: marża i marża po stratach od
 * 1 stycznia do końca wybranego okresu, liczone tym samym podsumowaniem co raport.
 */
@Service
@Transactional(readOnly = true)
public class ProfitTargetService {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    private final ProfitTargetRepository targets;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final ProfitTargetMapper mapper;
    private final PeriodSummaryService summaryService;

    public ProfitTargetService(ProfitTargetRepository targets, StoreRepository stores, AppUserRepository users,
            ProfitTargetMapper mapper, PeriodSummaryService summaryService) {
        this.targets = targets;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
        this.summaryService = summaryService;
    }

    /** Cele sklepu, od najnowszego roku. */
    public List<ProfitTargetResponse> list(UUID storeId) {
        return targets.findByStoreIdAndDeletedAtIsNullOrderByYearDesc(storeId).stream().map(mapper::toResponse)
                .toList();
    }

    /** Zapisuje cel roku (poprzedni dostaje znacznik); oba pola puste usuwają cel. Zwraca wszystkie cele. */
    @Transactional
    public List<ProfitTargetResponse> save(UUID storeId, int year, ProfitTargetRequest request, UUID userId) {
        checkYear(year);
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        AppUser by = userId != null ? users.getReferenceById(userId) : null;
        ProfitTarget current = targets.findByStoreIdAndYearAndDeletedAtIsNull(storeId, year).orElse(null);
        boolean empty = request.marginValue() == null && request.marginAfterLossesValue() == null;
        if (current != null && !empty && current.sameAs(request.marginValue(), request.marginAfterLossesValue())) {
            return list(storeId);
        }
        if (current != null) {
            current.delete(by);
            targets.saveAndFlush(current);
        }
        if (!empty) {
            targets.save(new ProfitTarget(store, year, request.marginValue(), request.marginAfterLossesValue(), by));
        }
        targets.flush();
        return list(storeId);
    }

    /** Postęp roku, w którym kończy się okres: od 1 stycznia do {@code to}. Bez celu nic nie liczy. */
    public ProfitTargetProgressResponse progress(UUID storeId, LocalDate to) {
        int year = to.getYear();
        checkYear(year);
        LocalDate from = LocalDate.of(year, 1, 1);
        ProfitTarget target = targets.findByStoreIdAndYearAndDeletedAtIsNull(storeId, year).orElse(null);
        if (target == null) {
            return new ProfitTargetProgressResponse(year, from, to, null, null, null);
        }
        PeriodSummaryResponse.Figures total = summaryService.summary(storeId, from, to).total();
        return new ProfitTargetProgressResponse(year, from, to, mapper.toResponse(target), total.marginValue(),
                total.marginAfterLossesValue());
    }

    private static void checkYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw ApiException.badRequest("Podaj rok od " + MIN_YEAR + " do " + MAX_YEAR);
        }
    }
}
