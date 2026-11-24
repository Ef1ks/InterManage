package com.intermanage.backend.cadtop.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.cadtop.component.CadSchedule;
import com.intermanage.backend.cadtop.dto.CadPrices;
import com.intermanage.backend.cadtop.entity.CadPlan;
import com.intermanage.backend.cadtop.entity.CadStoreRegion;
import com.intermanage.backend.cadtop.entity.CadTopFile;
import com.intermanage.backend.cadtop.repository.CadPlanRepository;
import com.intermanage.backend.cadtop.repository.CadStoreRegionRepository;
import com.intermanage.backend.cadtop.repository.CadTopFileRepository;
import com.intermanage.backend.common.component.StoreTime;

/**
 * Aktualne ceny cadencier z plików TOP. Cadencier obowiązuje w całej sieci, więc sklep bez własnego rejonu (np. bez
 * klucza API portalu) dostaje plik rejonu, w którym jest najwięcej sklepów.
 */
@Service
public class CadPriceService {

    private final CadPlanRepository plans;
    private final CadStoreRegionRepository regions;
    private final CadTopFileRepository files;

    public CadPriceService(CadPlanRepository plans, CadStoreRegionRepository regions, CadTopFileRepository files) {
        this.plans = plans;
        this.regions = regions;
        this.files = files;
    }

    /** Plik TOP i dzień wejścia jego numeru. */
    public record Current(CadTopFile file, LocalDate from) {
    }

    /**
     * Plik TOP obowiązujący sklep danego dnia: najpóźniejszy numer z planu, który już wszedł i ma plik. Pusty bez planu
     * albo pliku.
     */
    @Transactional(readOnly = true)
    public Optional<Current> currentOn(UUID storeId, LocalDate day) {
        return regions.findById(storeId).map(CadStoreRegion::getRegion)
                .or(regions::findMostCommonRegion)
                .flatMap(region -> {
                    Map<String, CadTopFile> byNumber = files.findByRegionAndReplacedAtIsNull(region).stream()
                            .collect(Collectors.toMap(CadTopFile::getNrCad, Function.identity()));
                    return schedule(plans.findActive()).entries().stream()
                            .filter(entry -> !entry.startsOn().isAfter(day) && byNumber.containsKey(entry.nrCad()))
                            .reduce((first, second) -> second)
                            .map(entry -> new Current(byNumber.get(entry.nrCad()), entry.startsOn()));
                });
    }

    /**
     * Dzisiejsze ceny cadencier podanych artykułów sklepu: po ITM8, a bez niego po EAN-ie (z kartoteki, listy cadencier
     * i obowiązujących multikodów), bo sklep bywa z tym towarem pod innym ITM8.
     */
    @Transactional(readOnly = true)
    public CadPrices current(UUID storeId, Collection<String> itm8s) {
        Current current = currentOn(storeId, LocalDate.now(StoreTime.ZONE)).orElse(null);
        if (current == null) {
            return CadPrices.NONE;
        }
        String number = current.file().getNrCad();
        Map<String, CadPrices.Price> prices = itm8s.isEmpty() ? Map.of()
                : files.findPrices(storeId, current.file().getId(), itm8s.toArray(String[]::new)).stream()
                        .collect(Collectors.toMap(CadTopFileRepository.ArticlePrice::getItm8,
                                price -> new CadPrices.Price(number, current.from(), price.getPurchasePrice(),
                                        price.getSalePrice())));
        return new CadPrices(number, current.from(), prices);
    }

    static CadSchedule schedule(List<CadPlan> active) {
        return CadSchedule.of(active.stream().map(plan -> new CadSchedule.Plan(plan.getYear(),
                plan.getEntries().stream().map(entry -> new CadSchedule.PlanEntry(entry.getNrCad(),
                        entry.getStartsOn(), entry.getComment())).toList())).toList());
    }
}
