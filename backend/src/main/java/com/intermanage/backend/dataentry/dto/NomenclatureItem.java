package com.intermanage.backend.dataentry.dto;

/** Pozycja nomenklatury sklepu: kod 2, 4 albo 6 cyfr (rejon, rodzina, podrodzina) i nazwa. */
public record NomenclatureItem(String code, String name) {
}
