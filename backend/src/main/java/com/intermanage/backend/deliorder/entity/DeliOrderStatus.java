package com.intermanage.backend.deliorder.entity;

/** Status zamówienia garmażu u dostawcy. Nazwa stałej trafia do bazy. */
public enum DeliOrderStatus {
    /** Przyszło i nikt u dostawcy go jeszcze nie otworzył - dostawca widzi je jako nowe. */
    NEW,
    /** Dostawca je otworzył; czeka na realizację. */
    READ,
    FULFILLED;

    /** Jeszcze niezrealizowane: można je poprawiać, łączyć, zmieniać datę i usuwać. */
    public boolean isOpen() {
        return this != FULFILLED;
    }
}
