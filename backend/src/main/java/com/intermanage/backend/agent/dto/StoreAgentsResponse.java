package com.intermanage.backend.agent.dto;

import java.util.List;

/**
 * Ustawienie sklepu (praca z folderami przez agenta albo przeglądarkę) i jego agenci. {@code latestVersion}: wersja
 * agenta z paczki w „Do pobrania” (brak = żadnej): agenty pobierają ją same, a plik w folderze podmienia człowiek.
 */
public record StoreAgentsResponse(boolean enabled, List<StoreAgentResponse> agents, String latestVersion) {
}
