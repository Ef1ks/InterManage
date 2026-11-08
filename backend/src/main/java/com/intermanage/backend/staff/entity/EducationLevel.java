package com.intermanage.backend.staff.entity;

/** Ukończona szkoła i lata wliczane do stażu urlopowego (art. 155 par. 1 KP). Nazwa stałej trafia do bazy. */
public enum EducationLevel {

    NONE(0),
    VOCATIONAL(3),
    SECONDARY_VOCATIONAL(5),
    GENERAL_SECONDARY(4),
    POST_SECONDARY(6),
    HIGHER(8);

    private final int years;

    EducationLevel(int years) {
        this.years = years;
    }

    public int years() {
        return years;
    }
}
