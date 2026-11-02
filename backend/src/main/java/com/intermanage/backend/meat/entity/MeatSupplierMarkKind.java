package com.intermanage.backend.meat.entity;

/** Znak dostawcy z dokumentu WZ, po którym rozpoznajemy go następnym razem; nazwa stałej trafia do bazy. */
public enum MeatSupplierMarkKind {
    /** Weterynaryjny numer identyfikacyjny zakładu, np. PL 08624302. */
    VET,
    BDO,
    NIP
}
