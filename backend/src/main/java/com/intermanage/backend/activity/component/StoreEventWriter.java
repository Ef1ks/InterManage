package com.intermanage.backend.activity.component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.activity.entity.StoreEvent;
import com.intermanage.backend.activity.repository.StoreEventRepository;

/** Zapis paczki zdarzeń w jednej transakcji (osobny bean, żeby {@code @Transactional} działało z bufora). */
@Component
public class StoreEventWriter {

    private final StoreEventRepository events;

    public StoreEventWriter(StoreEventRepository events) {
        this.events = events;
    }

    @Transactional
    public void save(List<StoreEventRecord> batch) {
        events.saveAll(batch.stream().map(StoreEvent::of).toList());
    }

    /** Powtórka zdarzeń, z których część jest już w bazie: zapisuje tylko brakujące. */
    @Transactional
    public void saveMissing(List<StoreEventRecord> batch) {
        Set<UUID> existing = new HashSet<>();
        events.findAllById(batch.stream().map(StoreEventRecord::id).toList())
                .forEach(event -> existing.add(event.getId()));
        events.saveAll(batch.stream().filter(record -> !existing.contains(record.id())).map(StoreEvent::of).toList());
    }
}
