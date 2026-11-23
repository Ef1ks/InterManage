package com.intermanage.backend.task.dto;

/**
 * Liczniki do menu i pulpitu: moje zadania do zrobienia, (dla zlecającego) wykonane czekające na przyjęcie i otwarte
 * zadania całego sklepu.
 */
public record TaskCounts(long open, long waiting, long storeOpen) {
}
