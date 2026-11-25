package com.intermanage.backend.agent.dto;

import com.intermanage.backend.agent.entity.AgentFeature;

/** Funkcja agenta w sklepie: włączona albo wyłączona. */
public record AgentFeatureResponse(AgentFeature feature, boolean enabled) {
}
