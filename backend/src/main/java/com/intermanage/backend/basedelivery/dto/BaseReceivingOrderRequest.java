package com.intermanage.backend.basedelivery.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Numer zamówienia wpisany ręcznie (np. dostawę wprowadził człowiek). */
public record BaseReceivingOrderRequest(
        @NotNull @Pattern(regexp = "\\d{1,10}", message = "Nieprawidłowy kod dostawcy") String supplierCode,
        @NotNull @Pattern(regexp = "\\s*\\d{1,10}\\s*", message = "Numer zamówienia to same cyfry") String orderNumber) {
}
