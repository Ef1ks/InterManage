package com.intermanage.backend.task.dto;

/** Liczniki do menu i pulpitu: moje zadania do zrobienia i (dla zlecającego) wykonane czekające na przyjęcie. */
public record TaskCounts(long open, long waiting) {
}
