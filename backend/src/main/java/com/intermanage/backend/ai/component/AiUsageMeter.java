package com.intermanage.backend.ai.component;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.intermanage.backend.ai.dto.AiUsageSummary;
import com.intermanage.backend.ai.entity.AiFeature;
import com.intermanage.backend.ai.entity.AiUsage;
import com.intermanage.backend.ai.repository.AiUsageRepository;

import tools.jackson.databind.JsonNode;

/**
 * Licznik tokenów modeli AI. Serwis otwiera zakres żądania ({@link #scope}) i wywołuje w nim model przez
 * {@link #call}; klient modelu po każdej odpowiedzi woła {@link #record}, co zapisuje wiersz {@code ai_usage} przy
 * sklepie z zakresu i dolicza tokeny do zakresu (z niego bierze się zużycie w odpowiedzi). Zakres to
 * {@link ScopedValue}, więc zadanie w innym wątku (paczki równolegle) trzeba wywołać przez {@link #call} z tym
 * samym zakresem. Błąd zapisu nie psuje odpowiedzi modelu.
 */
@Component
public class AiUsageMeter {

    private static final Logger log = LoggerFactory.getLogger(AiUsageMeter.class);
    private static final ScopedValue<Scope> CURRENT = ScopedValue.newInstance();

    private final AiUsageRepository usages;

    public AiUsageMeter(AiUsageRepository usages) {
        this.usages = usages;
    }

    public static Scope scope(UUID storeId, UUID userId) {
        return new Scope(storeId, userId);
    }

    /** Wywołanie modelu w zakresie {@code scope} (także w innym wątku). */
    public static <T> T call(Scope scope, Callable<T> task) throws Exception {
        return ScopedValue.where(CURRENT, scope).call(task::call);
    }

    /** Odpowiedź modelu {@code generateContent}: model z {@code modelVersion}, tokeny z {@code usageMetadata}. */
    public void record(AiFeature feature, String requestedModel, JsonNode response) {
        try {
            JsonNode usage = response.path("usageMetadata");
            String model = response.path("modelVersion").isString() && !response.path("modelVersion").asString()
                    .isBlank() ? response.path("modelVersion").asString() : requestedModel;
            int prompt = usage.path("promptTokenCount").asInt(0);
            int output = usage.path("candidatesTokenCount").asInt(0);
            int thoughts = usage.path("thoughtsTokenCount").asInt(0);
            int cached = usage.path("cachedContentTokenCount").asInt(0);
            int total = usage.path("totalTokenCount").asInt(prompt + output + thoughts);
            Scope scope = CURRENT.isBound() ? CURRENT.get() : null;
            if (scope != null) {
                scope.add(model, prompt, output, thoughts, total);
            }
            usages.save(new AiUsage(scope != null ? scope.storeId : null, scope != null ? scope.userId : null,
                    feature, model, prompt, output, thoughts, cached, total));
        } catch (RuntimeException e) {
            log.warn("Nie udało się zapisać zużycia tokenów AI", e);
        }
    }

    /** Zakres jednego żądania: sklep, osoba i suma tokenów jego zapytań do modelu. */
    public static final class Scope {

        private final UUID storeId;
        private final UUID userId;
        private final LongAdder requests = new LongAdder();
        private final LongAdder prompt = new LongAdder();
        private final LongAdder output = new LongAdder();
        private final LongAdder thoughts = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final Set<String> models = ConcurrentHashMap.newKeySet();

        private Scope(UUID storeId, UUID userId) {
            this.storeId = storeId;
            this.userId = userId;
        }

        private void add(String model, int promptTokens, int outputTokens, int thoughtTokens, int totalTokens) {
            models.add(model);
            requests.increment();
            prompt.add(promptTokens);
            output.add(outputTokens);
            thoughts.add(thoughtTokens);
            total.add(totalTokens);
        }

        /** Zużycie żądania albo {@code null}, gdy model nie odpowiedział ani razu. */
        public AiUsageSummary summary() {
            if (requests.sum() == 0) {
                return null;
            }
            return new AiUsageSummary(String.join(", ", new TreeSet<>(models)), requests.sum(), prompt.sum(),
                    output.sum(), thoughts.sum(), total.sum());
        }
    }
}
