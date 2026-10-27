package com.intermanage.backend.deliprice.entity;

/** Jak wpisano skład kalkulacji. Nazwa stałej trafia do bazy. */
public enum CalculationMode {
    /** Ilości składników na 1 kg gotowego wyrobu. */
    PER_KG,
    /** Ilości na cały przepis; z przepisu wychodzi {@code batchYield} kg wyrobu. */
    PER_BATCH
}
