package com.intermanage.backend.dto;

/** Zmiana przypisania EAN wykryta przy imporcie; pola {@code previous*} puste, gdy danego rodzaju zmiany nie było. */
public record EanChangeResponse(
        String itm8,
        String name,
        String ean,
        String previousEan,
        String previousItm8,
        String previousName) {
}
