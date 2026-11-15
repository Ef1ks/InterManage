package com.intermanage.backend.mea.dto;

/** Zapis kluczy API sklepu. Pusty {@code apiSecret} zostawia dotychczasowy (sekret nie wraca do przeglądarki). */
public record MeaSettingsRequest(String apiKey, String apiSecret) {
}
