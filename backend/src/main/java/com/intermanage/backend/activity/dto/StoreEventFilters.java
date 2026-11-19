package com.intermanage.backend.activity.dto;

import java.util.List;
import java.util.UUID;

/** Do wyboru w filtrach dziennika: moduły i osoby z dostępem do sklepu. */
public record StoreEventFilters(List<Option> modules, List<Person> users) {

    public record Option(String key, String label) {
    }

    public record Person(UUID id, String name) {
    }
}
