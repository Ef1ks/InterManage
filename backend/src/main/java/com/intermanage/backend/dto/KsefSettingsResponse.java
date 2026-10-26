package com.intermanage.backend.dto;

import java.util.List;

/**
 * Ustawienia KSeF sklepu; {@code connection} puste, dopóki nikt nie wgrał certyfikatu. {@code companies}: firmy
 * połączone w innych sklepach - właściciel może do nich podłączyć ten sklep bez certyfikatu.
 */
public record KsefSettingsResponse(
        KsefConnectionResponse connection,
        List<KsefInternalIdResponse> internalIds,
        List<KsefSeenInternalId> seenInternalIds,
        List<KsefSkippedSupplierResponse> skippedSuppliers,
        List<KsefCompanyResponse> companies) {
}
