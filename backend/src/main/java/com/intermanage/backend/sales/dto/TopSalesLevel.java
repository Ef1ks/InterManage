package com.intermanage.backend.sales.dto;

/** Na czym liczymy top: jeden poziom nomenklatury naraz albo cały sklep bez podziału. */
public enum TopSalesLevel {
    STORE(0),
    REGION(2),
    FAMILY(4),
    SUBFAMILY(6);

    private final int codeLength;

    TopSalesLevel(int codeLength) {
        this.codeLength = codeLength;
    }

    public int codeLength() {
        return codeLength;
    }
}
