package com.intermanage.backend.supplier.dto;

/**
 * Dane do makra zakładania dostawcy w Mercalys, już przycięte do pól programu: wielkie litery bez polskich znaków,
 * nazwa w dwóch polach po 30 znaków, hasło do 8 znaków, kod pocztowy z samych cyfr. {@code code}: pierwszy wolny kod
 * sklepu (lista dostawców i kody zarezerwowane makrami). {@code fromGus}: dane z rejestru REGON; bez nich
 * {@code gusProblem} mówi dlaczego, a nazwa jest z faktury. {@code previous}: dla tego NIP-u skopiowano już makro, a lista
 * dostawców go jeszcze nie ma.
 */
public record MercalysSupplierDraft(
        String nip,
        String code,
        String keyword,
        String name1,
        String name2,
        String street,
        String postalCode,
        String city,
        boolean fromGus,
        String gusProblem,
        MercalysSupplierCreationResponse previous) {
}
