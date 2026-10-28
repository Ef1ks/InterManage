package com.intermanage.backend.document.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Zamknięty raport przyjęć, w którym przyjęcie poszło razem ze swoim okresem (lista PZ pokazuje przy nim kłódkę). */
public record DocumentReportClosing(UUID id, LocalDate periodFrom, LocalDate periodTo, Instant closedAt) {
}
