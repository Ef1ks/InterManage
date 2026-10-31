package com.intermanage.backend.dataentry.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.Measure;
import com.intermanage.backend.dataentry.component.SuggestionModel;
import com.intermanage.backend.dataentry.component.SuggestionPrompt;
import com.intermanage.backend.dataentry.dto.SuggestionRequest;
import com.intermanage.backend.dataentry.dto.SuggestionResponse;
import com.intermanage.backend.dataentry.entity.DataEntrySuggestion;
import com.intermanage.backend.dataentry.entity.SuggestionStatus;
import com.intermanage.backend.dataentry.repository.DataEntryCatalogRepository;
import com.intermanage.backend.dataentry.repository.DataEntrySuggestionRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.entity.NomenclatureImport;
import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.nomenclature.repository.NomenclatureImportRepository;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * "Uzupełnij z AI": nazwa, marka, podrodzina, rodzaj sprzedaży i waga dla nowych artykułów z faktury.
 *
 * <p>Warstwa bez modelu (kartoteka sklepu): podobne nazwy (pg_trgm) głosują swoją podrodziną z wagą
 * podobieństwo², z premią dla podrodzin, w których dostawca faktury ma towar, a inne produkty producenta (prefiks EAN
 * 9, 8 albo 7 cyfr) dają rozkład podrodzin. Gdy producent ma co najmniej {@value #CERTAIN_MIN_PRODUCTS} produktów, ich
 * {@value #CERTAIN_MIN_SHARE} w jednej podrodzinie i to samo wskazują podobne nazwy, podrodzina jest pewna bez modelu
 * (w teście na 234 pozycjach z faktur: 18% pozycji, trafne w 100%).
 *
 * <p>Model (Gemini) dostaje paczki po {@value #BATCH} pozycji równolegle. Jego podrodzina wchodzi do tabeli, gdy
 * zgadza się z podobnymi nazwami albo z producentem, a pewność to co najmniej {@value #AGREED_MIN_CONFIDENCE}
 * (w teście 66% pozycji, trafne w 89%); inaczej podrodzinę wybiera człowiek z {@value #PROPOSALS} propozycji
 * (prawdziwa wśród nich w 82% takich pozycji). Gdy model nie odpowie, propozycje idą tylko z kartoteki.
 */
@Service
public class DataEntrySuggestionService {

    private static final Logger log = LoggerFactory.getLogger(DataEntrySuggestionService.class);

    static final int BATCH = 20;
    static final int SIMILAR_LIMIT = 15;
    static final int SIMILAR_IN_PROMPT = 10;
    static final int EXAMPLES_IN_PROMPT = 10;
    static final int PRODUCER_MIN_PRODUCTS = 3;
    static final int CERTAIN_MIN_PRODUCTS = 5;
    static final double CERTAIN_MIN_SHARE = 0.7;
    static final double AGREED_MIN_CONFIDENCE = 0.9;
    static final double SUPPLIER_BOOST = 10;
    static final int PROPOSALS = 5;

    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;
    private final DataEntryCatalogRepository catalog;
    private final NomenclatureRepository nomenclatures;
    private final NomenclatureImportRepository nomenclatureImports;
    private final DataEntrySuggestionRepository suggestions;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final SuggestionModel model;
    /** Instrukcja z drzewem nomenklatury sklepu, do następnego importu nomenklatury. */
    private final Map<UUID, CachedInstruction> instructions = new ConcurrentHashMap<>();

    public DataEntrySuggestionService(PlatformTransactionManager transactionManager, DataEntryCatalogRepository catalog,
            NomenclatureRepository nomenclatures, NomenclatureImportRepository nomenclatureImports,
            DataEntrySuggestionRepository suggestions, StoreRepository stores, AppUserRepository users,
            SuggestionModel model) {
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.catalog = catalog;
        this.nomenclatures = nomenclatures;
        this.nomenclatureImports = nomenclatureImports;
        this.suggestions = suggestions;
        this.stores = stores;
        this.users = users;
        this.model = model;
    }

    private record CachedInstruction(UUID importId, String text) {
    }

    /** Inne produkty producenta: rozkład podrodzin (od najczęstszej), łączna liczba i produkty z markami. */
    record Producer(List<Map.Entry<String, Integer>> subfamilies, int count,
            List<DataEntryCatalogRepository.ProducerProduct> products) {

        String top() {
            return subfamilies.getFirst().getKey();
        }

        double share() {
            return (double) subfamilies.getFirst().getValue() / count;
        }
    }

    /** Wskazania kartoteki dla pozycji: ranking podobnych nazw, producent (może być {@code null}), pewność. */
    record Layer(List<String> ranked, Producer producer, boolean certain) {

        String top() {
            return ranked.isEmpty() ? null : ranked.getFirst();
        }
    }

    private record Context(Map<String, String> nomenclature, String instruction,
            List<List<DataEntryCatalogRepository.SimilarProduct>> similar, List<Layer> layers) {
    }

    /** Wynik paczki: odpowiedzi modelu po pozycji albo powód, dla którego ich nie ma. */
    private record BatchResult(Map<Integer, SuggestionModel.Answer> answers, String error) {
    }

    public SuggestionResponse suggest(UUID storeId, SuggestionRequest request, UUID userId) {
        List<SuggestionRequest.Item> items = request.items();
        Context context = readTransaction.execute(status -> context(storeId, request));

        String[] error = {model.available() ? null : "Model AI nie jest skonfigurowany: propozycje tylko z kartoteki"};
        Map<Integer, SuggestionModel.Answer> answers = new HashMap<>();
        if (model.available()) {
            for (Map.Entry<Integer, BatchResult> batch : askModel(request, context).entrySet()) {
                if (batch.getValue().error() != null) {
                    error[0] = batch.getValue().error() + ": część propozycji tylko z kartoteki, spróbuj ponownie";
                }
                batch.getValue().answers().forEach((id, answer) -> answers.put(batch.getKey() + id, answer));
            }
        }

        return writeTransaction.execute(status -> {
            Store store = stores.getReferenceById(storeId);
            AppUser user = userId != null ? users.getReferenceById(userId) : null;
            BrandBook brands = brandBook(storeId, answers.values());
            List<SuggestionResponse.Suggestion> result = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                result.add(suggestion(store, user, request, items.get(index), context, context.layers().get(index),
                        answers.get(index), brands));
            }
            boolean modelUsed = answers.size() == items.size();
            return new SuggestionResponse(modelUsed, modelUsed ? null
                    : error[0] != null ? error[0] : "Model nie podał propozycji dla części pozycji", result);
        });
    }

    private Context context(UUID storeId, SuggestionRequest request) {
        List<Nomenclature> tree = nomenclatures.findAllByStore(storeId);
        if (tree.stream().noneMatch(item -> item.getLevel() == NomenclatureLevel.SUBFAMILY)) {
            throw ApiException.conflict("Wgraj nomenklaturę sklepu w Imporcie danych, żeby AI mogło ją proponować");
        }
        Map<String, String> nomenclature = new LinkedHashMap<>();
        tree.forEach(item -> nomenclature.put(item.getCode(), item.getName()));
        UUID importId = nomenclatureImports.findLatest(storeId, Limit.of(1)).stream().findFirst()
                .map(NomenclatureImport::getId).orElse(null);
        CachedInstruction cached = instructions.compute(storeId, (id, current) ->
                current != null && Objects.equals(current.importId(), importId) ? current
                        : new CachedInstruction(importId, SuggestionPrompt.systemInstruction(nomenclature)));

        List<SuggestionRequest.Item> items = request.items();
        catalog.useSimilarityThreshold();
        List<List<DataEntryCatalogRepository.SimilarProduct>> similar = new ArrayList<>();
        items.forEach(item -> similar.add(new ArrayList<>()));
        String[] names = items.stream().map(item -> Measure.plain(item.invoiceName()).replace('.', ' '))
                .toArray(String[]::new);
        for (DataEntryCatalogRepository.SimilarProduct row : catalog.findSimilar(storeId, names, SIMILAR_LIMIT)) {
            similar.get(row.getIdx() - 1).add(row);
        }

        Map<String, Integer> supplier = new HashMap<>();
        String nip = request.sellerNip() != null ? request.sellerNip().replaceAll("\\D", "") : "";
        if (nip.length() == 10) {
            catalog.countSubfamiliesOfSupplier(storeId, nip)
                    .forEach(row -> supplier.put(row.getCode(), (int) row.getCount()));
        }

        List<String> eans = items.stream().map(item -> producerEan(item.ean())).toList();
        String[] prefixes = eans.stream().filter(Objects::nonNull).map(ean -> ean.substring(0, 7)).distinct()
                .toArray(String[]::new);
        List<DataEntryCatalogRepository.ProducerProduct> producerProducts = prefixes.length == 0 ? List.of()
                : catalog.findByEanPrefixes(storeId, prefixes);

        List<Layer> layers = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            List<String> ranked = vote(similar.get(index), supplier);
            Producer producer = producer(eans.get(index), producerProducts);
            boolean certain = producer != null && producer.count() >= CERTAIN_MIN_PRODUCTS
                    && producer.share() >= CERTAIN_MIN_SHARE && producer.top().equals(ranked.isEmpty() ? null
                            : ranked.getFirst());
            layers.add(new Layer(ranked, producer, certain));
        }
        return new Context(nomenclature, cached.text(), similar, layers);
    }

    /** Podrodziny podobnych nazw od najmocniejszej: podobieństwo², premia za podrodziny towaru dostawcy. */
    static List<String> vote(List<DataEntryCatalogRepository.SimilarProduct> similar, Map<String, Integer> supplier) {
        Map<String, Double> score = new HashMap<>();
        similar.forEach(row -> score.merge(row.getSubfamilyCode(), row.getSimilarity() * row.getSimilarity(),
                Double::sum));
        int total = supplier.values().stream().mapToInt(Integer::intValue).sum();
        if (total > 0) {
            score.replaceAll((code, value) -> value * (1 + SUPPLIER_BOOST * supplier.getOrDefault(code, 0) / total));
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
    }

    /** EAN-13 producenta (bez zer wiodących, nie z puli sklepowej 2…); inne kody nie wskazują producenta. */
    static String producerEan(String ean) {
        String key = DataEntryService.key(DataEntryService.ean(ean));
        return key != null && key.length() == 13 && !key.startsWith("2") ? key : null;
    }

    /** Produkty z najdłuższym wspólnym prefiksem (9, 8, potem 7 cyfr), którego ma co najmniej 3 produkty. */
    static Producer producer(String ean, List<DataEntryCatalogRepository.ProducerProduct> products) {
        if (ean == null) {
            return null;
        }
        for (int length = 9; length >= 7; length--) {
            String prefix = ean.substring(0, length);
            List<DataEntryCatalogRepository.ProducerProduct> same = products.stream()
                    .filter(product -> product.getEan().startsWith(prefix) && !product.getEan().equals(ean)).toList();
            if (same.size() >= PRODUCER_MIN_PRODUCTS) {
                Map<String, Integer> counts = same.stream().collect(Collectors.toMap(
                        DataEntryCatalogRepository.ProducerProduct::getSubfamilyCode, product -> 1, Integer::sum));
                List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .toList();
                return new Producer(sorted, same.size(), same);
            }
        }
        return null;
    }

    /** Paczki po {@value #BATCH} pozycji naraz; klucz to indeks pierwszej pozycji paczki. */
    private Map<Integer, BatchResult> askModel(SuggestionRequest request, Context context) {
        List<SuggestionPrompt.Example> examples = request.examples() == null ? List.of()
                : request.examples().stream().limit(EXAMPLES_IN_PROMPT)
                        .map(example -> new SuggestionPrompt.Example(example.invoiceName(), example.catalogName(),
                                example.brand()))
                        .toList();
        Map<Integer, Future<BatchResult>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int start = 0; start < request.items().size(); start += BATCH) {
                int from = start;
                int to = Math.min(start + BATCH, request.items().size());
                List<SuggestionPrompt.Item> batch = new ArrayList<>();
                for (int index = from; index < to; index++) {
                    batch.add(promptItem(request.items().get(index), context, index));
                }
                String prompt = SuggestionPrompt.prompt(request.sellerName(), examples, batch);
                futures.put(from, executor.submit(() -> ask(context.instruction(), prompt, to - from)));
            }
        }
        Map<Integer, BatchResult> results = new LinkedHashMap<>();
        futures.forEach((start, future) -> {
            try {
                results.put(start, future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.put(start, new BatchResult(Map.of(), "Przerwano zapytanie do modelu"));
            } catch (ExecutionException e) {
                log.warn("Błąd zapytania do modelu", e.getCause());
                results.put(start, new BatchResult(Map.of(), "Model nie odpowiedział"));
            }
        });
        return results;
    }

    private BatchResult ask(String instruction, String prompt, int size) {
        try {
            Map<Integer, SuggestionModel.Answer> answers = new HashMap<>();
            for (SuggestionModel.Answer answer : model.suggest(instruction, prompt)) {
                if (answer.id() >= 0 && answer.id() < size) {
                    answers.putIfAbsent(answer.id(), answer);
                }
            }
            return new BatchResult(answers, null);
        } catch (SuggestionModel.Unavailable e) {
            log.info("Model niedostępny: {}", e.getMessage());
            return new BatchResult(Map.of(), e.getMessage());
        }
    }

    private static SuggestionPrompt.Item promptItem(SuggestionRequest.Item item, Context context, int index) {
        Layer layer = context.layers().get(index);
        List<SuggestionPrompt.ProducerGroup> producer = new ArrayList<>();
        if (layer.producer() != null) {
            layer.producer().subfamilies().stream().limit(5).forEach(entry -> producer.add(
                    new SuggestionPrompt.ProducerGroup(entry.getKey(), entry.getValue(), layer.producer().products()
                            .stream().filter(product -> product.getSubfamilyCode().equals(entry.getKey()))
                            .map(product -> product.getName() + (product.getBrand() != null
                                    ? " (" + product.getBrand() + ")" : ""))
                            .limit(2).toList())));
        }
        List<SuggestionPrompt.Similar> similar = context.similar().get(index).stream().limit(SIMILAR_IN_PROMPT)
                .map(row -> new SuggestionPrompt.Similar(row.getName(), row.getBrand(), row.getSubfamilyCode()))
                .toList();
        return new SuggestionPrompt.Item(item.invoiceName().strip(), item.ean(), item.invoiceUnit(), producer,
                layer.producer() != null ? layer.producer().count() : 0, similar);
    }

    private SuggestionResponse.Suggestion suggestion(Store store, AppUser user, SuggestionRequest request,
            SuggestionRequest.Item item, Context context, Layer layer, SuggestionModel.Answer answer,
            BrandBook brands) {
        Set<String> subfamilies = context.nomenclature().keySet().stream().filter(code -> code.length() == 6)
                .collect(Collectors.toSet());
        String modelSubfamily = answer != null && subfamilies.contains(answer.subfamily()) ? answer.subfamily() : null;
        List<String> alternatives = answer == null || answer.alternatives() == null ? List.of()
                : answer.alternatives().stream().filter(subfamilies::contains)
                        .filter(code -> !code.equals(modelSubfamily)).distinct().toList();
        String producerTop = layer.producer() != null ? layer.producer().top() : null;
        double confidence = answer != null && answer.confidence() != null ? answer.confidence() : 0;

        SuggestionStatus status;
        String subfamily;
        if (layer.certain()) {
            status = SuggestionStatus.CERTAIN;
            subfamily = producerTop;
        } else if (modelSubfamily != null && confidence >= AGREED_MIN_CONFIDENCE
                && (modelSubfamily.equals(layer.top()) || modelSubfamily.equals(producerTop))) {
            status = SuggestionStatus.AGREED;
            subfamily = modelSubfamily;
        } else {
            status = SuggestionStatus.TO_CHECK;
            subfamily = null;
        }

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (subfamily != null) {
            ordered.add(subfamily);
        }
        if (modelSubfamily != null) {
            ordered.add(modelSubfamily);
        }
        ordered.addAll(alternatives);
        if (modelSubfamily == null && layer.top() != null) {
            // Bez modelu na zmianę: najmocniejsze podobne nazwy i najczęstsza podrodzina producenta.
            ordered.add(layer.top());
            if (producerTop != null) {
                ordered.add(producerTop);
            }
        }
        ordered.addAll(layer.ranked());
        if (layer.producer() != null) {
            layer.producer().subfamilies().forEach(entry -> ordered.add(entry.getKey()));
        }
        Set<String> producerCodes = layer.producer() == null ? Set.of() : layer.producer().subfamilies().stream()
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        List<SuggestionResponse.Proposal> proposals = ordered.stream().filter(subfamilies::contains)
                .limit(PROPOSALS).map(code -> {
                    List<SuggestionResponse.Source> sources = new ArrayList<>();
                    if (code.equals(modelSubfamily) || alternatives.contains(code)) {
                        sources.add(SuggestionResponse.Source.MODEL);
                    }
                    if (layer.ranked().contains(code)) {
                        sources.add(SuggestionResponse.Source.NAMES);
                    }
                    if (producerCodes.contains(code)) {
                        sources.add(SuggestionResponse.Source.PRODUCER);
                    }
                    return new SuggestionResponse.Proposal(code, sources);
                }).toList();

        Measure parsed = Measure.fromInvoice(item.invoiceName(), item.invoiceUnit());
        Measure fromModel = answer != null ? Measure.of(answer.unit(), answer.netContent()) : null;
        Measure measure = parsed != null ? parsed : fromModel != null ? fromModel : Measure.piece();
        boolean unitMismatch = parsed != null && fromModel != null && (!parsed.unit().equals(fromModel.unit())
                || parsed.netContent().compareTo(fromModel.netContent()) != 0);
        String name = answer != null && answer.name() != null ? Measure.normalizeName(answer.name(), measure) : null;
        String brand = answer != null ? brands.canonical(answer.brand(), layer.producer()) : null;

        BigDecimal modelConfidence = answer != null && answer.confidence() != null
                ? BigDecimal.valueOf(Math.max(0, Math.min(1, answer.confidence()))).setScale(3, RoundingMode.HALF_UP)
                : null;
        DataEntrySuggestion.Evidence evidence = new DataEntrySuggestion.Evidence(layer.top(), producerTop,
                layer.producer() != null ? layer.producer().count() : null,
                layer.producer() != null ? BigDecimal.valueOf(layer.producer().share()).setScale(4, RoundingMode.HALF_UP)
                        : null,
                layer.certain());
        DataEntrySuggestion.ModelAnswer modelAnswer = answer == null
                ? new DataEntrySuggestion.ModelAnswer(model.available() ? model.name() : null, false, null, null, null,
                        null, null, null, null)
                : new DataEntrySuggestion.ModelAnswer(model.name(), true, answer.name(), answer.brand(),
                        truncate(answer.subfamily(), 6), answer.alternatives(), modelConfidence,
                        truncate(answer.unit(), 4),
                        answer.netContent() != null ? answer.netContent().setScale(3, RoundingMode.HALF_UP) : null);
        DataEntrySuggestion saved = suggestions.save(new DataEntrySuggestion(store, request.invoiceId(),
                truncate(DataEntryService.ean(item.ean()), 14), item.invoiceName().strip(), item.invoiceUnit(),
                evidence, modelAnswer,
                new DataEntrySuggestion.Proposal(status, proposals.stream().map(SuggestionResponse.Proposal::code)
                        .toList(), name, brand, subfamily, measure.unit(), measure.netContent()),
                user));
        return new SuggestionResponse.Suggestion(item.key(), saved.getId(), name, brand, measure.unit(),
                measure.netContent(), unitMismatch, subfamily, status, modelConfidence, proposals);
    }

    /**
     * Zapisy marek z kartoteki: marka od modelu zapisana tak jak u producenta (prefiks EAN), a bez niego jak
     * najczęściej w kartotece; porównanie bez wielkości liter i polskich znaków.
     */
    private record BrandBook(Map<String, String> catalog) {

        String canonical(String brand, Producer producer) {
            String key = brandKey(brand);
            if (key == null) {
                return null;
            }
            if (producer != null) {
                String own = producer.products().stream().map(DataEntryCatalogRepository.ProducerProduct::getBrand)
                        .filter(Objects::nonNull).filter(name -> key.equals(brandKey(name)))
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                        .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
                if (own != null) {
                    return own;
                }
            }
            return catalog.getOrDefault(key, key);
        }
    }

    private BrandBook brandBook(UUID storeId, Collection<SuggestionModel.Answer> answers) {
        String[] keys = answers.stream().map(answer -> brandKey(answer.brand())).filter(Objects::nonNull).distinct()
                .toArray(String[]::new);
        if (keys.length == 0) {
            return new BrandBook(Map.of());
        }
        Map<String, Map.Entry<String, Long>> best = new HashMap<>();
        for (DataEntryCatalogRepository.CodeCount row : catalog.countBrands(storeId, keys)) {
            String key = brandKey(row.getCode());
            best.merge(key, Map.entry(row.getCode(), row.getCount()),
                    (a, b) -> a.getValue() >= b.getValue() ? a : b);
        }
        return new BrandBook(best.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getKey())));
    }

    /** Marka do porównań: wielkie litery bez polskich znaków i kropek; "-" i pusta to brak marki. */
    static String brandKey(String brand) {
        if (brand == null) {
            return null;
        }
        String key = Measure.plain(brand).replace('.', ' ').replaceAll("\\s+", " ").strip();
        return key.isEmpty() || key.equals("-") ? null : key;
    }

    private static String truncate(String value, int length) {
        return value == null ? null : value.length() > length ? value.substring(0, length) : value;
    }
}
