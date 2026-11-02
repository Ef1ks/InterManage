package com.intermanage.backend.deliproduction.dto;

import java.util.UUID;

public record ProductionArticleResponse(UUID id, String code, String name, String nomenclature, UUID schemaId) {
}
