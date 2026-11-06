package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;

/**
 * Linia przyjęcia wybranego do korekty, po wcześniejszych korektach (bez tej, którą wprowadzamy). {@code ean} to kod
 * z bonu, po którym program sklepu znajduje linię. {@code articleItm8} i {@code articleEan} to artykuł z kartoteki
 * o tym kodzie: mięso idzie w dokumentach pod lokalnym ITM8 ({@code 99…}), więc ITM8 linii bywa inny niż w kartotece;
 * {@code null}, gdy artykułu nie ma w kartotece.
 */
public record CorrectionDeliveryLine(String itm8, String ean, String name, String brand, BigDecimal quantity,
        BigDecimal purchasePrice, String articleItm8, String articleEan) {
}
