package com.intermanage.backend.cash.entity;

/** Rodzaj dokumentu kasowego; nazwa stałej trafia do bazy. */
public enum CashDocumentKind {

    /** Kasa przyjmie: wpływ gotówki. */
    KP("Kasa przyjmie", "Wpłacił"),
    /** Kasa wypłaci: wypłata gotówki. */
    KW("Kasa wypłaci", "Otrzymał");

    private final String label;
    private final String counterpartyRole;

    CashDocumentKind(String label, String counterpartyRole) {
        this.label = label;
        this.counterpartyRole = counterpartyRole;
    }

    public String label() {
        return label;
    }

    /** Kim jest druga strona na wydruku: wpłacający albo odbierający gotówkę. */
    public String counterpartyRole() {
        return counterpartyRole;
    }

    public boolean isReceipt() {
        return this == KP;
    }
}
