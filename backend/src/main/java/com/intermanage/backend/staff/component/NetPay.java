package com.intermanage.backend.staff.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.intermanage.backend.staff.entity.StaffProfile;

/**
 * Szacunek wypłaty netto za miesiąc z danych z Kadr, bez bazy. Stawki na 2026: składki pracownika na ubezpieczenia
 * emerytalne 9,76%, rentowe 1,5%, chorobowe 2,45% (ustawa o systemie ubezpieczeń społecznych, art. 22), zdrowotna 9%
 * od przychodu po składkach społecznych (ustawa o świadczeniach opieki zdrowotnej, art. 79), zaliczka PIT 12% minus
 * 300 zł przy PIT-2, koszty 250 zł albo 300 zł przy umowie o pracę i 20% przy zleceniu (ustawa o PIT, art. 22, 27, 32,
 * 41). Do 26 lat ulga dla młodych zwalnia z podatku (art. 21 ust. 1 pkt 148), a uczeń i student przy zleceniu nie
 * płaci składek. Wynagrodzenie chorobowe nie jest oskładkowane na ubezpieczenia społeczne, tylko na zdrowotne.
 * <p>
 * To szacunek jednego miesiąca: bez progu 32% (ponad 120 tys. zł w roku), limitu ulgi dla młodych (85 528 zł) i
 * rocznego limitu podstawy składek emerytalnej i rentowej. Sprawdza go {@code NetPayTests}.
 */
public final class NetPay {

    static final BigDecimal PENSION = new BigDecimal("0.0976");
    static final BigDecimal DISABILITY = new BigDecimal("0.015");
    static final BigDecimal SICKNESS = new BigDecimal("0.0245");
    static final BigDecimal HEALTH = new BigDecimal("0.09");
    static final BigDecimal TAX = new BigDecimal("0.12");
    static final BigDecimal TAX_REDUCTION = new BigDecimal("300");
    static final BigDecimal COSTS = new BigDecimal("250");
    static final BigDecimal HIGHER_COSTS = new BigDecimal("300");
    static final BigDecimal MANDATE_COSTS = new BigDecimal("0.20");
    static final int YOUTH_AGE = 26;

    private NetPay() {
    }

    /** Dane z Kadr, od których zależą potrącenia. {@code youth}: ulga dla młodych w tym miesiącu. */
    public record Settings(boolean pit2, boolean higherCosts, boolean youth, boolean student, boolean voluntarySickness,
            boolean birthDateKnown) {

        /** Bez profilu w Kadrach: PIT-2 złożony, bez ulg, bo tak jest najczęściej. */
        public static Settings of(StaffProfile profile, YearMonth month) {
            if (profile == null) {
                return new Settings(true, false, false, false, false, false);
            }
            LocalDate birth = profile.getBirthDate();
            // Ulga obowiązuje do dnia przed 26. urodzinami; w miesiącu urodzin liczymy ją jeszcze w całości.
            boolean young = birth != null && month.atDay(1).isBefore(birth.plusYears(YOUTH_AGE));
            return new Settings(profile.isPit2(), profile.isHigherCosts(), young && !profile.isYouthReliefWaived(),
                    young && profile.isStudent(), profile.isVoluntarySickness(), birth != null);
        }
    }

    /** Potrącenia i kwota na rękę; {@code notes} opisują, co wzięto pod uwagę. */
    public record Result(BigDecimal gross, BigDecimal social, BigDecimal health, BigDecimal tax, BigDecimal net,
            List<String> notes) {
    }

    /**
     * Umowa o pracę. {@code contributory}: wynagrodzenie za pracę i urlop z dodatkami (oskładkowane), {@code sickPay}:
     * wynagrodzenie chorobowe (bez składek społecznych).
     */
    public static Result employment(BigDecimal contributory, BigDecimal sickPay, Settings settings) {
        BigDecimal gross = contributory.add(sickPay);
        List<String> notes = new ArrayList<>();
        BigDecimal social = social(contributory, true);
        BigDecimal health = money(contributory.subtract(social).add(sickPay).multiply(HEALTH));
        BigDecimal costs = settings.higherCosts() ? HIGHER_COSTS : COSTS;
        notes.add("koszty uzyskania przychodu " + costs.toPlainString() + " zł"
                + (settings.higherCosts() ? " (podwyższone)" : ""));
        BigDecimal tax = tax(gross.subtract(social).subtract(costs), settings, true, notes);
        return result(gross, social, health, tax, notes, settings);
    }

    /**
     * Umowa zlecenia. {@code employedHere}: ta sama osoba ma umowę o pracę u tego pracodawcy, więc zlecenie podlega
     * wszystkim składkom jak praca (art. 8 ust. 2a ustawy o systemie ubezpieczeń społecznych), a kwotę zmniejszającą
     * bierze już umowa o pracę.
     */
    public static Result mandate(BigDecimal gross, Settings settings, boolean employedHere) {
        List<String> notes = new ArrayList<>();
        if (settings.student() && !employedHere) {
            notes.add("uczeń lub student do 26 lat: bez składek ZUS i zdrowotnej");
            BigDecimal tax = tax(gross.multiply(BigDecimal.ONE.subtract(MANDATE_COSTS)), settings, true, notes);
            return result(gross, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), tax, notes, settings);
        }
        boolean sickness = employedHere || settings.voluntarySickness();
        notes.add(sickness ? "składka chorobowa" + (employedHere ? " (umowa o pracę u nas)" : " (dobrowolna)")
                : "bez składki chorobowej (dobrowolna, nie zaznaczona w Kadrach)");
        BigDecimal social = social(gross, sickness);
        BigDecimal afterSocial = gross.subtract(social);
        BigDecimal health = money(afterSocial.multiply(HEALTH));
        BigDecimal costs = money(afterSocial.multiply(MANDATE_COSTS));
        notes.add("koszty uzyskania przychodu 20%");
        BigDecimal tax = tax(afterSocial.subtract(costs), settings, !employedHere, notes);
        return result(gross, social, health, tax, notes, settings);
    }

    private static Result result(BigDecimal gross, BigDecimal social, BigDecimal health, BigDecimal tax,
            List<String> notes, Settings settings) {
        if (!settings.birthDateKnown()) {
            notes.add("brak daty urodzenia w Kadrach: bez ulgi dla młodych");
        }
        BigDecimal net = gross.subtract(social).subtract(health).subtract(tax).setScale(2, RoundingMode.HALF_UP);
        return new Result(gross.setScale(2, RoundingMode.HALF_UP), social, health, tax, net, List.copyOf(notes));
    }

    private static BigDecimal social(BigDecimal base, boolean sickness) {
        BigDecimal sum = money(base.multiply(PENSION)).add(money(base.multiply(DISABILITY)));
        return sickness ? sum.add(money(base.multiply(SICKNESS))) : sum;
    }

    /** Zaliczka: podstawa i podatek do pełnych złotych (Ordynacja podatkowa, art. 63). */
    private static BigDecimal tax(BigDecimal base, Settings settings, boolean reduction, List<String> notes) {
        if (settings.youth()) {
            notes.add("ulga dla młodych (do 26 lat): bez podatku");
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal rounded = base.max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP);
        BigDecimal tax = rounded.multiply(TAX);
        if (reduction && settings.pit2()) {
            tax = tax.subtract(TAX_REDUCTION);
            notes.add("PIT-2: kwota zmniejszająca 300 zł");
        } else if (reduction) {
            notes.add("bez PIT-2: bez kwoty zmniejszającej");
        }
        return tax.max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP).setScale(2);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
