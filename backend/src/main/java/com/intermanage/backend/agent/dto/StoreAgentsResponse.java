package com.intermanage.backend.agent.dto;

import java.util.List;

/** Ustawienie sklepu (praca z folderami przez agenta albo przeglądarkę) i jego agenci. */
public record StoreAgentsResponse(boolean enabled, List<StoreAgentResponse> agents) {
}
