package com.intermanage.backend.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Przypięcie pozycji faktury do artykułu: {@code sellerNip} dostawcy z faktury, {@code code} to kod z kolumny EAN
 * (nazwa albo inny kod z faktury), {@code itm8} artykułu z kartoteki.
 */
public record ProductLinkRequest(@NotBlank(message = "Podaj NIP dostawcy") @Size(max = 20) String sellerNip,
        @NotBlank(message = "Podaj kod pozycji") @Size(max = 300) String code,
        @NotBlank(message = "Wybierz artykuł") @Size(max = 13) String itm8) {
}
