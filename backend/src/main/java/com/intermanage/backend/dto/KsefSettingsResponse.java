package com.intermanage.backend.dto;

import java.util.List;

/**
 * Ustawienia KSeF sklepu; {@code connection} puste, dopóki nikt nie wgrał certyfikatu. {@code companies}: firmy
 * połączone w innych sklepach, do których użytkownik ma dostęp - może do nich podłączyć ten sklep bez certyfikatu;
 * {@code otherStores}: odwrotnie, sklepy, które połączony sklep może podłączyć do swojej firmy.
 */
public record KsefSettingsResponse(
        KsefConnectionResponse connection,
        List<KsefInternalIdResponse> internalIds,
        List<KsefSeenInternalId> seenInternalIds,
        List<KsefSkippedSupplierResponse> skippedSuppliers,
        List<KsefCompanyResponse> companies,
        /** Inne aktywne sklepy użytkownika (tylko przy połączonym sklepie) - do podłączenia ich do tej firmy. */
        List<KsefCompanyStore> otherStores) {
}
