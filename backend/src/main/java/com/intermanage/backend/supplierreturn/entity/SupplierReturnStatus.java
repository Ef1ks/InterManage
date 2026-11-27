package com.intermanage.backend.supplierreturn.entity;

/** Stan zwrotu do dostawcy; nazwa trafia do bazy. */
public enum SupplierReturnStatus {

    /** Lista gotowa, dostawca jeszcze nie wie. */
    NEW("Do zgłoszenia"),
    REPORTED("Zgłoszony"),
    IN_PROGRESS("W trakcie"),
    /** Dostawca uznał zwrot, czeka się na korektę. */
    ACCEPTED("Uznany"),
    CORRECTED("Rozliczony korektą"),
    REJECTED("Odrzucony");

    private final String label;

    SupplierReturnStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Zwrot jeszcze nierozliczony: liczy się do licznika i dostaje automatyczne podpinanie korekt. */
    public boolean open() {
        return this != CORRECTED && this != REJECTED;
    }
}
