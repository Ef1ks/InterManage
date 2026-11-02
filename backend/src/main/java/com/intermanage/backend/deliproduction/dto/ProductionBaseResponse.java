package com.intermanage.backend.deliproduction.dto;

import java.util.List;

/**
 * Baza kart produkcji sklepu: schematy, artykuły (alfabetycznie) i pieczątka na kartach. {@code customStamp} = false,
 * gdy pieczątka to domyślna nazwa sklepu i NIP.
 */
public record ProductionBaseResponse(
        List<ProductionSchemaResponse> schemas,
        List<ProductionArticleResponse> articles,
        String stamp,
        boolean customStamp) {
}
