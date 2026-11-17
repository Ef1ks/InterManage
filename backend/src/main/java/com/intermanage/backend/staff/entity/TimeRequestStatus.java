package com.intermanage.backend.staff.entity;

/**
 * Stan wniosku: czeka na kierownika, zaakceptowany przez kierownika i czeka na kadry, zatwierdzony ostatecznie (liczy
 * się do czasu pracy), odrzucony, wycofany przez pracownika. Nazwa trafia do bazy.
 */
public enum TimeRequestStatus {
    PENDING,
    MANAGER_APPROVED,
    APPROVED,
    REJECTED,
    CANCELLED;

    public boolean open() {
        return this == PENDING || this == MANAGER_APPROVED;
    }
}
