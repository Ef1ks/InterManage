package com.intermanage.backend.collector.dto;

import java.util.UUID;

/** Ustawienie promocji z kolektora makrem; {@code agentId} pusty = wolny komputer sklepu. */
public record CollectorPromotionMacroRequest(UUID agentId) {
}
