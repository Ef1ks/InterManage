package com.intermanage.backend.store.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Kolejność kategorii menu bocznego: klucze grup z frontu, pusta lista = kolejność domyślna. */
public record SidebarOrderRequest(
        @NotNull(message = "Podaj kolejność kategorii")
        @Size(max = 30, message = "Za dużo kategorii")
        List<@NotNull @Pattern(regexp = "[a-z-]{1,40}", message = "Nieznana kategoria") String> groups) {
}
