package com.intermanage.backend.ksef.component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.ksef.entity.KsefApiBlock;
import com.intermanage.backend.ksef.entity.KsefApiCall;
import com.intermanage.backend.ksef.repository.KsefApiBlockRepository;
import com.intermanage.backend.ksef.repository.KsefApiCallRepository;

/**
 * Historia zapytań do KSeF i blokad w bazie: po restarcie backendu (każde wdrożenie) limiter wie, ile zapytań poszło
 * w ostatniej godzinie, i nie przekracza limitów KSeF, który liczy je dalej.
 */
@Component
public class KsefRateHistory implements KsefRateLimiter.History {

    /** Dłużej niż najdłuższe okno limitu (godzina), z zapasem. */
    private static final Duration RETENTION = Duration.ofHours(2);

    private final KsefApiCallRepository calls;
    private final KsefApiBlockRepository blocks;

    public KsefRateHistory(KsefApiCallRepository calls, KsefApiBlockRepository blocks) {
        this.calls = calls;
        this.blocks = blocks;
    }

    @Override
    public List<Instant> recentCalls(String nip, KsefRateLimiter.Operation operation, Instant since) {
        return calls.findCallTimes(nip, operation.name(), since);
    }

    @Override
    public void recordCall(String nip, KsefRateLimiter.Operation operation, Instant at) {
        calls.save(new KsefApiCall(nip, operation.name(), at));
    }

    @Override
    public Instant blockedUntil(String nip) {
        return blocks.findById(nip).map(KsefApiBlock::getBlockedUntil).orElse(null);
    }

    @Override
    @Transactional
    public void block(String nip, Instant until) {
        blocks.findById(nip).ifPresentOrElse(block -> block.setBlockedUntil(until),
                () -> blocks.save(new KsefApiBlock(nip, until)));
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
    public void cleanUp() {
        calls.deleteOlderThan(Instant.now().minus(RETENTION));
    }
}
