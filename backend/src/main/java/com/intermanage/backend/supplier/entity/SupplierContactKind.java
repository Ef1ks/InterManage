package com.intermanage.backend.supplier.entity;

/** Kto to jest u dostawcy. Nazwa stałej trafia do bazy, więc istniejących nie zmieniamy. */
public enum SupplierContactKind {
    REPRESENTATIVE,
    ORDERS,
    ACCOUNTING,
    COMPLAINTS,
    OTHER
}
