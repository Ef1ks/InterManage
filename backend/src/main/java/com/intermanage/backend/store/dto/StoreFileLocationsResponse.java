package com.intermanage.backend.store.dto;

import java.util.Map;

/** Ścieżki sklepu z wartością (klucz → wartość); puste się nie pojawiają. */
public record StoreFileLocationsResponse(Map<String, String> locations) {
}
