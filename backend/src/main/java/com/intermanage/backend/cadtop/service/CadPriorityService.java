package com.intermanage.backend.cadtop.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.cadtop.component.CadPriorityMacro;
import com.intermanage.backend.cadtop.dto.CadPriorityReport;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Alternative;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Item;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Run;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.RunResult;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Status;
import com.intermanage.backend.cadtop.dto.CadPriorityRequest;
import com.intermanage.backend.cadtop.entity.CadPriorityRun;
import com.intermanage.backend.cadtop.repository.CadPriorityRunRepository;
import com.intermanage.backend.cadtop.repository.CadPriorityRunRepository.Match;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * ITM8 z cadencier, które nie są priorytetowe w sklepie. Artykuł z obowiązującego pliku TOP, którego ITM8 nie jest
 * priorytetowym ITM8 żadnej karty z najnowszego raportu kartoteki, a jego EAN jest na karcie z innym ITM8 (zwykle
 * lokalnym 99…), trzeba przestawić w Mercalys. Karta z innym ITM8 z tego samego cadencier (zwykły zamiast paletowego)
 * jest w porządku. Wynik ustawienia widać dopiero w następnym raporcie kartoteki.
 */
@Service
public class CadPriorityService {

    private final CadPriceService prices;
    private final CadPriorityRunRepository runs;
    private final ProductImportRepository imports;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public CadPriorityService(CadPriceService prices, CadPriorityRunRepository runs, ProductImportRepository imports,
            StoreRepository stores, AppUserRepository users) {
        this.prices = prices;
        this.runs = runs;
        this.imports = imports;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public CadPriorityReport report(UUID storeId) {
        CadPriceService.Current current = prices.currentOn(storeId, LocalDate.now(StoreTime.ZONE)).orElse(null);
        LocalDateTime generatedAt = imports.findNewestReportGeneratedAt(storeId).orElse(null);
        List<UUID> newest = imports.findNewestReportIds(storeId, Limit.of(1));
        if (current == null || newest.isEmpty()) {
            return new CadPriorityReport(current == null ? null : current.file().getNrCad(),
                    current == null ? null : current.from(), generatedAt, 0, 0, 0, 0, CadPriorityMacro.READY, List.of());
        }
        UUID fileId = current.file().getId();
        UUID importId = newest.getFirst();
        CadPriorityRunRepository.Counts counts = runs.count(storeId, fileId, importId);
        List<Match> matches = runs.findMatches(storeId, fileId, importId);
        long matchedTops = matches.stream().map(Match::getTopItm8).distinct().count();
        long variants = matches.stream().filter(Match::getOtherTop).map(Match::getTopItm8).distinct().count();

        // Kolejność zapytania: w karcie najpierw najlepsze dopasowanie, potem droższy, czyli zwykły (nie paletowy).
        Map<String, List<Match>> byCard = matches.stream().filter(match -> !match.getOtherTop())
                .collect(Collectors.groupingBy(Match::getItm8, LinkedHashMap::new, Collectors.toList()));
        Map<String, CadPriorityRun> latestRuns = new HashMap<>();
        if (!byCard.isEmpty()) {
            runs.findActive(storeId, byCard.keySet()).forEach(run -> latestRuns.putIfAbsent(run.getItm8(), run));
        }
        List<Item> items = byCard.values().stream()
                .map(card -> item(card, latestRuns.get(card.getFirst().getItm8()), generatedAt)).toList();
        return new CadPriorityReport(current.file().getNrCad(), current.from(), generatedAt, counts.getTop(),
                counts.getTop() - counts.getMissing(), (int) variants, (int) (counts.getMissing() - matchedTops),
                CadPriorityMacro.READY, items);
    }

    private static Item item(List<Match> card, CadPriorityRun run, LocalDateTime catalogGeneratedAt) {
        // Zlecony wcześniej inny ITM8 z tym EAN-em zostaje przy wierszu jako wybrany.
        Match chosen = run == null ? card.getFirst() : card.stream()
                .filter(match -> match.getTopItm8().equals(run.getTopItm8())).findFirst().orElse(card.getFirst());
        List<Alternative> alternatives = card.stream().filter(match -> match != chosen)
                .map(match -> new Alternative(match.getTopItm8(), match.getTopName(), match.getTopPurchasePrice()))
                .toList();
        Status status = run == null || !run.getTopItm8().equals(chosen.getTopItm8()) ? Status.TODO
                : catalogGeneratedAt != null && catalogGeneratedAt.isAfter(
                        LocalDateTime.ofInstant(run.getCreatedAt(), StoreTime.ZONE)) ? Status.FAILED : Status.WAITING;
        return new Item(chosen.getTopItm8(), chosen.getTopEan(), chosen.getTopName(), chosen.getTopBrand(),
                chosen.getTopMark(), chosen.getTopPurchasePrice(), chosen.getTopSalePrice(), chosen.getItm8(),
                chosen.getEan(), chosen.getName(), chosen.getBrand(), chosen.getSubfamilyCode(), chosen.getSupplier(),
                chosen.getStock(), chosen.getSalePrice(), chosen.getPurchasePrice(),
                Boolean.TRUE.equals(chosen.getActive()), CadPriorityReport.Match.values()[chosen.getRank()],
                chosen.getTopKnown(), alternatives, status, status == Status.TODO ? null
                        : new Run(run.getId(), run.getSource() == CadPriorityRun.Source.MACRO, run.getCreatedAt(),
                                run.getCreatedBy() == null ? null : run.getCreatedBy().getDisplayName()));
    }

    /** „Wykonaj makro”: makro do schowka dla zaznaczonych artykułów, które przechodzą do czekających na raport. */
    @Transactional
    public RunResult macro(UUID storeId, CadPriorityRequest request, UUID userId) {
        if (!CadPriorityMacro.READY) {
            throw ApiException.conflict("Makro czeka na kroki w Mercalys. Do tego czasu ustaw ITM8 ręcznie.");
        }
        Chosen chosen = chosen(storeId, request);
        String macro = CadPriorityMacro.build(chosen.items().stream().map(item -> new CadPriorityMacro.Item(
                item.item().ean(), item.item().itm8(), item.topItm8())).toList());
        return save(storeId, chosen, CadPriorityRun.Source.MACRO, macro, userId);
    }

    /** „Ustawione”: ITM8 zmieniony w Mercalys ręcznie, artykuł czeka na następny raport kartoteki. */
    @Transactional
    public RunResult mark(UUID storeId, CadPriorityRequest request, UUID userId) {
        return save(storeId, chosen(storeId, request), CadPriorityRun.Source.MANUAL, null, userId);
    }

    @Transactional
    public CadPriorityReport undo(UUID storeId, Collection<UUID> ids, UUID userId) {
        AppUser by = users.getReferenceById(userId);
        runs.findInStore(storeId, ids).forEach(run -> run.delete(by));
        runs.flush();
        return report(storeId);
    }

    @Transactional
    public CadPriorityReport restore(UUID storeId, Collection<UUID> ids) {
        runs.findInStore(storeId, ids).forEach(CadPriorityRun::restore);
        runs.flush();
        return report(storeId);
    }

    private record ChosenItem(Item item, String topItm8) {
    }

    private record Chosen(String nrCad, List<ChosenItem> items) {
    }

    private Chosen chosen(UUID storeId, CadPriorityRequest request) {
        CadPriorityReport report = report(storeId);
        Map<String, Item> byCard = report.items().stream().collect(Collectors.toMap(Item::itm8, item -> item));
        Map<String, ChosenItem> result = new LinkedHashMap<>();
        for (CadPriorityRequest.Item wanted : request.items()) {
            Item item = byCard.get(wanted.itm8());
            if (item == null || !(item.topItm8().equals(wanted.topItm8()) || item.alternatives().stream()
                    .anyMatch(alternative -> alternative.itm8().equals(wanted.topItm8())))) {
                throw ApiException.conflict("Artykułu " + wanted.itm8() + " nie ma już na liście. Odśwież stronę.");
            }
            result.put(item.itm8(), new ChosenItem(item, wanted.topItm8()));
        }
        return new Chosen(report.nrCad(), List.copyOf(result.values()));
    }

    private RunResult save(UUID storeId, Chosen chosen, CadPriorityRun.Source source, String macro, UUID userId) {
        Store store = stores.getReferenceById(storeId);
        AppUser by = users.getReferenceById(userId);
        List<CadPriorityRun> saved = new ArrayList<>();
        chosen.items().forEach(item -> saved.add(new CadPriorityRun(store, item.topItm8(), item.item().itm8(),
                item.item().ean(), chosen.nrCad(), source, by)));
        runs.saveAllAndFlush(saved);
        return new RunResult(macro, saved.stream().map(CadPriorityRun::getId).toList(), report(storeId));
    }
}
