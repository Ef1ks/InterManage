package com.intermanage.backend.store.entity;

/**
 * Protokół generowany z tabeli wprowadzania danych jako plik PROTO{@link #number} dla programu sklepu. Nazwa stałej
 * trafia do bazy (protokoły ukryte w sklepie), więc istniejących nie zmieniamy.
 */
public enum ProtocolKind {

    LIQUIDATION(1, "Likwidacje"),
    STORE_NEEDS(2, "Potrzeby sklepu"),
    DONATION(3, "Darowizna"),
    ADVERTISING(4, "Reklama"),
    REPRESENTATION(5, "Reprezentacja"),
    TRANSFER(6, "MM");

    private final int number;
    private final String label;

    ProtocolKind(int number, String label) {
        this.number = number;
        this.label = label;
    }

    public int number() {
        return number;
    }

    public String label() {
        return label;
    }
}
