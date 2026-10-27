package com.intermanage.backend.entity;

/** Status zamówienia garmażu u dostawcy. Nazwa stałej trafia do bazy. */
public enum DeliOrderStatus {
    /** Przyszło i czeka na realizację - dostawca widzi je jako nowe. */
    NEW,
    FULFILLED
}
