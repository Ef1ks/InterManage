package com.intermanage.backend.supplier.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Zmiana karty dostawcy. Nazwa, NIP i adres zmieniają się tylko u dostawcy dodanego ręcznie (u dostawcy z listy
 * przychodzą z raportu i są tu pomijane). Puste pola czyszczą wartość.
 */
public record SupplierDetailsRequest(
        @Size(max = 200, message = "Maksymalnie 200 znaków") String name,
        @Pattern(regexp = "^[\\d\\s-]{0,20}$", message = "NIP to same cyfry, np. 9291243024") String nip,
        @Size(max = 255, message = "Maksymalnie 255 znaków") String address,
        @Size(max = 10, message = "Maksymalnie 10 znaków") String postalCode,
        @Size(max = 100, message = "Maksymalnie 100 znaków") String city,
        @Size(max = 490, message = "Maksymalnie 490 znaków") String website,
        @Email(message = "To nie wygląda na adres e-mail")
        @Size(max = 200, message = "Maksymalnie 200 znaków") String email,
        @Size(max = 50, message = "Maksymalnie 50 znaków") String phone,
        @Size(max = 5000, message = "Maksymalnie 5000 znaków") String notes) {
}
