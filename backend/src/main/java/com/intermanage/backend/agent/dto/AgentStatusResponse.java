package com.intermanage.backend.agent.dto;

import java.time.Instant;
import java.util.List;

/**
 * Stan agenta sklepu dla każdego z dostępem do sklepu. {@code active} = sklep wybrał pracę przez agenta i agent jest
 * online: przeglądarka zapisuje wtedy pliki z {@code folders} przez kolejkę, a pilnowanie folderów z {@code watches}
 * zostawia agentowi. Inaczej wszystko działa jak dotąd, w przeglądarce.
 */
public record AgentStatusResponse(boolean enabled, boolean present, boolean online, boolean active, String name,
        String version, String machineName, Instant lastSeenAt, List<String> folders, List<String> watches) {
}
