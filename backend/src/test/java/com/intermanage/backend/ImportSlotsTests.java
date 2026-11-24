package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.common.component.ImportSlots;

/** Najwyżej tyle importów naraz, ile miejsc; nieudany import też zwalnia miejsce. */
class ImportSlotsTests {

    @Test
    void runsAtMostGivenNumberAtOnce() throws Exception {
        ImportSlots slots = new ImportSlots(3);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        CountDownLatch full = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            threads.add(Thread.ofVirtual().start(() -> slots.call("test", started::incrementAndGet, () -> {
                most.accumulateAndGet(running.incrementAndGet(), Math::max);
                full.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                return null;
            })));
        }
        assertThat(full.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(started).hasValue(3);
        release.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(started).hasValue(10);
        assertThat(most).hasValue(3);
    }

    @Test
    void failedImportReleasesSlot() {
        ImportSlots slots = new ImportSlots(1);
        assertThatThrownBy(() -> slots.call("test", () -> { }, () -> {
            throw new IllegalArgumentException("zły plik");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(slots.<String>call("test", () -> { }, () -> "ok")).isEqualTo("ok");
    }
}
