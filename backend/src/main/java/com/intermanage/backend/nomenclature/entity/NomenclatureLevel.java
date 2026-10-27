package com.intermanage.backend.nomenclature.entity;

/** Poziom nomenklatury. Nazwa stałej trafia do bazy ({@code nomenclatures.level}). */
public enum NomenclatureLevel {
    /** Rejon, kod z 2 cyfr, np. "01" (01_DELIKATESY). */
    REGION(2),
    /** Rodzina, kod z 4 cyfr, np. "0101" (PIECZYWO CHRUPKIE). */
    FAMILY(4),
    /** Podrodzina, kod z 6 cyfr, np. "010101" (PIECZYWO TWARDE). Do niej należy produkt. */
    SUBFAMILY(6);

    private final int codeLength;

    NomenclatureLevel(int codeLength) {
        this.codeLength = codeLength;
    }

    public int codeLength() {
        return codeLength;
    }

    public static NomenclatureLevel ofCode(String code) {
        for (NomenclatureLevel level : values()) {
            if (level.codeLength == code.length()) {
                return level;
            }
        }
        throw new IllegalArgumentException("Kod nomenklatury ma 2, 4 albo 6 cyfr: " + code);
    }
}
