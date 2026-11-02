package com.intermanage.backend.deliproduction.dto;

import jakarta.validation.constraints.Size;

/** Pieczątka zakładu (linie oddzielone znakiem nowej linii); pusta = nazwa sklepu i NIP. */
public record ProductionSettingsRequest(@Size(max = 500, message = "Najwyżej 500 znaków") String stamp) {
}
