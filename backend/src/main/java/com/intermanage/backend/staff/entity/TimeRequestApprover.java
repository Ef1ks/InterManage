package com.intermanage.backend.staff.entity;

/**
 * Kto ostatecznie zatwierdza wnioski pracowników (nadgodziny, wcześniejsze wyjście, spóźnienie…), żeby liczyły się do
 * czasu pracy: kierownik działu albo kadry ({@code STAFF_RECORDS:EDIT}). Nazwa trafia do bazy.
 */
public enum TimeRequestApprover {
    MANAGER,
    HR
}
