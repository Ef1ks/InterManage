package com.intermanage.backend.ai.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.ai.entity.AiFeature;
import com.intermanage.backend.ai.entity.AiUsage;

public interface AiUsageRepository extends JpaRepository<AiUsage, UUID> {

    /** Sumy zużycia sklepu w okresie [{@code from}, {@code to}) według modelu i funkcji. */
    @Query("""
            select u.model as model, u.feature as feature, count(u) as requests,
                   sum(u.promptTokens) as promptTokens, sum(u.outputTokens) as outputTokens,
                   sum(u.thoughtTokens) as thoughtTokens, sum(u.cachedTokens) as cachedTokens,
                   sum(u.totalTokens) as totalTokens
            from AiUsage u
            where u.storeId = :storeId and u.createdAt >= :from and u.createdAt < :to
            group by u.model, u.feature
            order by sum(u.totalTokens) desc
            """)
    List<Totals> sumByModel(UUID storeId, Instant from, Instant to);

    interface Totals {
        String getModel();

        AiFeature getFeature();

        long getRequests();

        long getPromptTokens();

        long getOutputTokens();

        long getThoughtTokens();

        long getCachedTokens();

        long getTotalTokens();
    }
}
