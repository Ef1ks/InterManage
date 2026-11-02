package com.intermanage.backend.deliproduction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Parametry procesu na karcie produkcji: temperatura pieczenia, temperatura wewnętrzna po wypieku, czas i temperatura
 * schładzania oraz ważność w dniach. {@code null} = na karcie "-". Ten sam zestaw ma schemat i (jako kopię z chwili
 * dodania) pozycja karty.
 */
@Embeddable
public record ProcessParameters(
        @Column(name = "baking_temperature") Integer bakingTemperature,
        @Column(name = "internal_temperature") Integer internalTemperature,
        @Column(name = "cooling_time") Integer coolingTime,
        @Column(name = "cooling_temperature") Integer coolingTemperature,
        @Column(name = "shelf_life_days") Integer shelfLifeDays) {

    public static final ProcessParameters EMPTY = new ProcessParameters(null, null, null, null, null);
}
