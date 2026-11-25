package com.intermanage.backend.cadtop.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.cadtop.component.CadPriorityMacro;
import com.intermanage.backend.cadtop.dto.CadPriorityReport;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Alternative;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Conflict;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Item;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Run;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.RunResult;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Status;
import com.intermanage.backend.cadtop.dto.CadPriorityReport.Task;
import com.intermanage.backend.cadtop.dto.CadPriorityRequest;
import com.intermanage.backend.cadtop.entity.CadPriorityRun;
import com.intermanage.backend.cadtop.entity.CadPrioritySettings;
import com.intermanage.backend.cadtop.repository.CadPriorityRunRepository;
import com.intermanage.backend.cadtop.repository.CadPriorityRunRepository.Match;
import com.intermanage.backend.cadtop.repository.CadPrioritySettingsRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * ITM8 z cadencier, które nie są priorytetowe w sklepie. Artykuł z obowiązującego pliku TOP, którego ITM8 nie jest
 * priorytetowym ITM8 żadnej karty z najnowszego raportu kartoteki, a jego EAN jest na karcie z innym ITM8 (zwykle
 * lokalnym 99…), trzeba przestawić w Mercalys. Karta z innym ITM8 z tego samego cadencier (zwykły zamiast paletowego)
 * jest w porządku. Do tego karty z priorytetowym ITM8 z cadencier, które są nieaktywne: trzeba je aktywować. Wynik
 * ustawienia widać dopiero w następnym raporcie kartoteki.
 */
@Service
public class CadPriorityService {

    /** Starsze zlecenie nie mówi już nic o artykule, który wrócił na listę (np. znów stracił aktywność). */
    private static final Duration RUN_COUNTS = Duration.ofDays(7);

    private final CadPriceService prices;
    private final CadPriorityRunRepository runs;
    private final CadPrioritySettingsRepository settings;
    private final ProductImportRepository imports;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public CadPriorityService(CadPriceService prices, CadPriorityRunRepository runs,
            CadPrioritySettingsRepository settings, ProductImportRepository imports, StoreRepository stores,
            AppUserRepository users) {
        this.prices = prices;
        this.runs = runs;
        this.settings = settings;
        this.imports = imports;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public CadPriorityReport report(UUID storeId) {
        CadPriceService.Current current = prices.currentOn(storeId, LocalDate.now(StoreTime.ZONE)).orElse(null);
        LocalDateTime generatedAt = imports.findNewestReportGeneratedAt(storeId).orElse(null);
        boolean automatic = automatic(storeId);
        List<UUID> newest = imports.findNewestReportIds(storeId, Limit.of(1));
        if (current == null || newest.isEmpty()) {
            return new CadPriorityReport(current == null ? null : current.file().getNrCad(),
                    current == null ? null : current.from(), generatedAt, 0, 0, 0, 0, automatic, List.of());
        }
        UUID fileId = current.file().getId();
        UUID importId = newest.getFirst();
        CadPriorityRunRepository.Counts counts = runs.count(storeId, fileId, importId);
        List<Match> matches = runs.findMatches(storeId, fileId, importId);
        long matchedTops = matches.stream().map(Match::getTopItm8).distinct().count();
        long variants = matches.stream().filter(Match::getOtherTop).map(Match::getTopItm8).distinct().count();

        // Kolejność zapytania: w karcie najpierw najlepsze dopasowanie, potem droższy, czyli zwykły (nie paletowy).
        Map<String, List<Match>> byCard = Stream.concat(matches.stream().filter(match -> !match.getOtherTop()),
                runs.findInactive(storeId, fileId, importId).stream()).collect(Collectors.groupingBy(Match::getItm8, LinkedHashMap::new, Collectors.toList()));
        Map<String, CadPriorityRun> latestRuns = new HashMap<>();
        if (!byCard.isEmpty()) {
            runs.findActive(storeId, byCard.keySet()).forEach(run -> latestRuns.putIfAbsent(run.getItm8(), run));
        }
        Map<String, Conflict> conflicts = conflicts(storeId, importId, byCard);
        List<Item> items = byCard.values().stream().map(card -> item(card, latestRuns.get(card.getFirst().getItm8()),
                generatedAt, conflicts)).toList();
        return new CadPriorityReport(current.file().getNrCad(), current.from(), generatedAt, counts.getTop(),
                counts.getTop() - counts.getMissing() - counts.getInactive(), (int) variants,
                (int) (counts.getMissing() - matchedTops), automatic, items);
    }

    /**
     * ITM8 z cadencier, które dane sklepu pokazują na innej karcie: klucz = karta + ITM8 z cadencier. Liczone dla
     * wszystkich par karty (także alternatyw), żeby wybór innego ITM8 też to widział.
     */
    private Map<String, Conflict> conflicts(UUID storeId, UUID importId, Map<String, List<Match>> byCard) {
        List<Match> pairs = byCard.values().stream().flatMap(List::stream)
                .filter(match -> !match.getTopItm8().equals(match.getItm8())).toList();
        if (pairs.isEmpty()) {
            return Map.of();
        }
        Map<String, Conflict> result = new HashMap<>();
        runs.findAssignedElsewhere(storeId, importId, pairs.stream().map(Match::getTopItm8).toArray(String[]::new),
                pairs.stream().map(Match::getItm8).toArray(String[]::new))
                .forEach(found -> result.putIfAbsent(conflictKey(found.getItm8(), found.getTopItm8()),
                        new Conflict(found.getEan(), found.getOtherItm8(), found.getOtherName(), null)));
        return result;
    }

    private static String conflictKey(String card, String topItm8) {
        return card + "|" + topItm8;
    }

    private static Item item(List<Match> card, CadPriorityRun run, LocalDateTime catalogGeneratedAt,
            Map<String, Conflict> conflicts) {
        // Zlecony wcześniej inny ITM8 z tym EAN-em zostaje przy wierszu jako wybrany.
        Match chosen = run == null ? card.getFirst() : card.stream()
                .filter(match -> match.getTopItm8().equals(run.getTopItm8())).findFirst().orElse(card.getFirst());
        List<Alternative> alternatives = card.stream().filter(match -> match != chosen)
                .map(match -> new Alternative(match.getTopItm8(), match.getTopName(), match.getTopPurchasePrice()))
                .toList();
        Status status = status(run, chosen.getTopItm8(), catalogGeneratedAt);
        // Makro trafiło na „Ten kod ITM jest już przyznany”: zostaje do ręcznej poprawy bez względu na wiek zlecenia.
        Conflict known = conflicts.get(conflictKey(chosen.getItm8(), chosen.getTopItm8()));
        String reason = run != null && run.getTopItm8().equals(chosen.getTopItm8())
                && CadPriorityMacro.assignedElsewhere(run.getFailure()) ? run.getFailure() : null;
        Conflict conflict = known == null && reason == null ? null
                : known == null ? new Conflict(null, null, null, reason)
                        : new Conflict(known.ean(), known.otherItm8(), known.otherName(), reason);
        boolean active = Boolean.TRUE.equals(chosen.getActive());
        Task task = chosen.getTopItm8().equals(chosen.getItm8()) ? Task.ACTIVE
                : active ? Task.PRIORITY : Task.PRIORITY_AND_ACTIVE;
        return new Item(chosen.getTopItm8(), chosen.getTopEan(), chosen.getTopName(), chosen.getTopBrand(),
                chosen.getTopMark(), chosen.getTopPurchasePrice(), chosen.getTopSalePrice(), chosen.getItm8(),
                chosen.getEan(), chosen.getName(), chosen.getBrand(), chosen.getSubfamilyCode(), chosen.getSupplier(),
                chosen.getStock(), chosen.getSalePrice(), chosen.getPurchasePrice(),
                active, task, CadPriorityReport.Match.values()[chosen.getRank()],
                chosen.getTopKnown(), alternatives, status, status == Status.TODO ? null
                        : new Run(run.getId(), run.getSource(), run.getCreatedAt(), run.getCreatedBy() == null ? null
                                : run.getCreatedBy().getDisplayName(), run.getFailure()), conflict);
    }

    private static Status status(CadPriorityRun run, String topItm8, LocalDateTime catalogGeneratedAt) {
        if (run == null || !run.getTopItm8().equals(topItm8)) {
            return Status.TODO;
        }
        boolean reported = catalogGeneratedAt != null
                && catalogGeneratedAt.isAfter(LocalDateTime.ofInstant(run.getCreatedAt(), StoreTime.ZONE));
        if (!reported && run.getFailure() == null) {
            return Status.WAITING;
        }
        return run.getCreatedAt().isAfter(Instant.now().minus(RUN_COUNTS)) ? Status.FAILED : Status.TODO;
    }

    private boolean automatic(UUID storeId) {
        return settings.findById(storeId).map(CadPrioritySettings::isAutomatic).orElse(false);
    }

    @Transactional
    public CadPriorityReport setAutomatic(UUID storeId, boolean enabled, UUID userId) {
        CadPrioritySettings value = settings.findById(storeId).orElseGet(() -> new CadPrioritySettings(storeId));
        value.update(enabled, users.getReferenceById(userId));
        settings.saveAndFlush(value);
        return report(storeId);
    }

    /** „Wykonaj makro”: makro do schowka dla zaznaczonych artykułów, które przechodzą do czekających na raport. */
    @Transactional
    public RunResult macro(UUID storeId, CadPriorityRequest request, UUID userId) {
        Chosen chosen = withoutConflicts(chosen(storeId, request));
        return save(storeId, chosen, CadPriorityRun.Source.MACRO, CadPriorityMacro.build(chosen.macroItems()), userId);
    }

    /** Artykuły z listy do makra na komputerze sklepu (409, gdy któregoś już na niej nie ma). */
    @Transactional(readOnly = true)
    public Chosen chosenForComputer(UUID storeId, CadPriorityRequest request) {
        return withoutConflicts(chosen(storeId, request));
    }

    /** ITM8 na innej karcie Mercalys odrzuci: takie artykuły poprawia się ręcznie, nie makrem. */
    private static Chosen withoutConflicts(Chosen chosen) {
        chosen.items().stream().filter(ChosenItem::conflicted).findFirst().ifPresent(item -> {
            throw ApiException.conflict("ITM8 " + CadPriorityMacro.key(item.topItm8()) + " jest już na innej karcie"
                    + " w Mercalys. Artykuł " + CadPriorityMacro.key(item.item().itm8())
                    + " popraw ręcznie i oznacz jako ustawiony.");
        });
        return chosen;
    }

    /** Zlecenia makra, które przyjął komputer sklepu ({@code userId} puste = zlecił je serwer). */
    @Transactional
    public void assignToJob(UUID storeId, Chosen chosen, UUID jobId, UUID userId) {
        runs.saveAllAndFlush(runs(storeId, chosen,
                userId == null ? CadPriorityRun.Source.AGENT : CadPriorityRun.Source.MACRO, jobId, userId));
    }

    /**
     * Artykuły do poprawienia po aktualizacji danych: tylko z dzisiejszego raportu kartoteki. Karta z kilkoma ITM8 do
     * wyboru czeka na człowieka (chyba że już wybrał), a pominięta przez makro i cofnięta ręcznie nie wraca sama.
     */
    @Transactional(readOnly = true)
    public Optional<Chosen> automaticItems(UUID storeId, int limit) {
        CadPriorityReport report = report(storeId);
        if (report.catalogGeneratedAt() == null
                || !report.catalogGeneratedAt().toLocalDate().equals(LocalDate.now(StoreTime.ZONE))) {
            return Optional.empty();
        }
        Set<String> withdrawn = runs.findWithdrawnSince(storeId,
                report.catalogGeneratedAt().atZone(StoreTime.ZONE).toInstant());
        List<ChosenItem> items = report.items().stream()
                .filter(item -> item.status() == Status.TODO
                        || item.status() == Status.FAILED && item.run().failure() == null)
                .filter(item -> item.alternatives().isEmpty() || item.run() != null)
                .filter(item -> item.conflict() == null)
                .filter(item -> !withdrawn.contains(item.itm8())).limit(limit)
                .map(item -> new ChosenItem(item, item.topItm8())).toList();
        return items.isEmpty() ? Optional.empty() : Optional.of(new Chosen(report.nrCad(), items));
    }

    /** Koniec makra: pominięte dostają powód, a to, czego makro nie zrobiło, wraca do ustawienia. */
    @Transactional
    public void settle(UUID jobId, boolean done, Set<String> completed, Map<String, String> skipped) {
        for (CadPriorityRun run : runs.findByJobIdAndDeletedAtIsNull(jobId)) {
            String card = CadPriorityMacro.key(run.getItm8());
            if (skipped.containsKey(card)) {
                run.fail(skipped.get(card));
            } else if (!done && !completed.contains(card)) {
                run.delete(null);
            }
        }
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

    public record ChosenItem(Item item, String topItm8) {

        /** Konflikt dotyczy ITM8 wybranego przy wierszu; inny wybrany ITM8 z cadencier może być wolny. */
        boolean conflicted() {
            return item.conflict() != null && item.topItm8().equals(topItm8);
        }
    }

    public record Chosen(String nrCad, List<ChosenItem> items) {

        public List<CadPriorityMacro.Item> macroItems() {
            return items.stream().map(item -> new CadPriorityMacro.Item(item.item().ean(), item.item().itm8(),
                    item.topItm8())).toList();
        }
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
        List<CadPriorityRun> saved = runs.saveAllAndFlush(runs(storeId, chosen, source, null, userId));
        return new RunResult(macro, saved.stream().map(CadPriorityRun::getId).toList(), report(storeId));
    }

    private List<CadPriorityRun> runs(UUID storeId, Chosen chosen, CadPriorityRun.Source source, UUID jobId,
            UUID userId) {
        Store store = stores.getReferenceById(storeId);
        AppUser by = userId == null ? null : users.getReferenceById(userId);
        return chosen.items().stream().map(item -> new CadPriorityRun(store, item.topItm8(), item.item().itm8(),
                item.item().ean(), chosen.nrCad(), source, jobId, by)).toList();
    }
}
