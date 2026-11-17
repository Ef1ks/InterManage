package com.intermanage.backend.staff.entity;

/**
 * Rodzaj wniosku pracownika. Wnioski o odcinek karty wskazują odcinek ({@link #segment}) i po ostatecznej akceptacji
 * stają się decyzją ({@link #decision}); zgłoszenie L4 ma zakres dni i trafia do Kadr jako zwolnienie. Nazwa trafia do
 * bazy.
 */
public enum TimeRequestKind {

    /** Praca poza grafikiem z własnej inicjatywy, za zgodą pracodawcy (art. 151 § 1 KP): nadgodziny. */
    OVERTIME(TimeSegmentKind.OVERTIME, TimeDecisionKind.EMPLOYEE_REQUEST, false),
    /** Odpracowanie wcześniejszego wyjścia prywatnego (art. 151²¹ § 2 KP): praca, ale nie nadgodziny. */
    WORK_OFF(TimeSegmentKind.OVERTIME, TimeDecisionKind.WORKED_OFF, false),
    /** Wyjście w celu załatwienia spraw osobistych na pisemny wniosek, do odpracowania (art. 151²¹ § 1 KP). */
    PRIVATE_EXIT(TimeSegmentKind.SHORTFALL, TimeDecisionKind.PRIVATE_EXIT, false),
    /** Spóźnienie albo wcześniejsze wyjście z ważnej przyczyny: usprawiedliwione, bez wynagrodzenia. */
    LATE_OR_EARLY(TimeSegmentKind.SHORTFALL, TimeDecisionKind.JUSTIFIED_UNPAID, true),
    /** Pilna sprawa rodzinna, choroba lub wypadek (art. 148¹ KP, 16 h w roku): 50% wynagrodzenia. */
    FORCE_MAJEURE(TimeSegmentKind.SHORTFALL, TimeDecisionKind.JUSTIFIED_HALF, true),
    /** Opieka nad dzieckiem do 14 lat (art. 188 KP, 16 h albo 2 dni w roku): płatne. */
    CHILD_CARE(TimeSegmentKind.SHORTFALL, TimeDecisionKind.JUSTIFIED, false),
    /** Inna płatna nieobecność (badania lekarskie, krwiodawstwo): płatne. */
    OTHER_PAID(TimeSegmentKind.SHORTFALL, TimeDecisionKind.JUSTIFIED, true),
    /** Zgłoszenie zwolnienia lekarskiego: zakres dni, zatwierdzają zawsze kadry (wpis L4 w Kadrach). */
    SICK_LEAVE(null, null, false);

    private final TimeSegmentKind segment;
    private final TimeDecisionKind decision;
    private final boolean reasonRequired;

    TimeRequestKind(TimeSegmentKind segment, TimeDecisionKind decision, boolean reasonRequired) {
        this.segment = segment;
        this.decision = decision;
        this.reasonRequired = reasonRequired;
    }

    public TimeSegmentKind segment() {
        return segment;
    }

    public TimeDecisionKind decision() {
        return decision;
    }

    public boolean reasonRequired() {
        return reasonRequired;
    }

    /** Polska nazwa na wydruk. */
    public String label() {
        return switch (this) {
            case OVERTIME -> "Praca w godzinach nadliczbowych z własnej inicjatywy";
            case WORK_OFF -> "Odpracowanie wyjścia prywatnego (art. 151²¹ § 2 KP)";
            case PRIVATE_EXIT -> "Zwolnienie od pracy na sprawy osobiste z odpracowaniem (art. 151²¹ § 1 KP)";
            case LATE_OR_EARLY -> "Usprawiedliwienie spóźnienia albo wcześniejszego wyjścia";
            case FORCE_MAJEURE -> "Zwolnienie z powodu siły wyższej (art. 148¹ KP)";
            case CHILD_CARE -> "Zwolnienie na opiekę nad dzieckiem (art. 188 KP)";
            case OTHER_PAID -> "Usprawiedliwiona nieobecność płatna";
            case SICK_LEAVE -> "Zgłoszenie niezdolności do pracy (zwolnienie lekarskie)";
        };
    }
}
