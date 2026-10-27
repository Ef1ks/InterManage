package com.intermanage.backend.dto;

/** IDWew z pobranych faktur, z nazwą odbiorcy i liczbą faktur; {@code assigned} = sklep ma go już na liście. */
public record KsefSeenInternalId(String internalId, String name, long invoiceCount, boolean assigned) {
}
