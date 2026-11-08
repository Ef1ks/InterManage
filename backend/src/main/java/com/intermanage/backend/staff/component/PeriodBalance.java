package com.intermanage.backend.staff.component;

import java.time.LocalDate;
import java.util.List;

/**
 * Saldo okresu rozliczeniowego pracownika: za dni, które minęły i mają kartę czasu pracy, liczy się czas z karty (praca
 * i zatwierdzone nadgodziny, {@code TimeCard}), za pozostałe grafik. Bez bazy, testy: {@code ScheduleLawTests}.
 * <p>
 * Z karty odcinamy to, co przekracza limit dobowy: to nadgodziny dobowe (art. 151 § 1 pkt 2 KP), które rozlicza się
 * czasem wolnym (art. 151²) albo dodatkiem (art. 151¹), więc nie wchodzą do salda wymiaru, żeby nie liczyć ich dwa razy.
 * Godziny ponad grafik w limicie dobowym wchodzą do salda i wyrównuje się je mniejszym planem w kolejnych miesiącach
 * okresu. To nasza interpretacja: przepis nie mówi wprost, że kolejne miesiące można ułożyć krócej.
 */
public final class PeriodBalance {

    private PeriodBalance() {
    }

    /**
     * Jeden dzień. {@code norm}: wymiar dnia w minutach (już z etatem i obniżeniem za urlop). {@code planned}: minuty
     * zmiany z grafiku, pusty bez zmiany albo pod nieobecnością. {@code actual}: minuty z karty, pusty, gdy dzień nie ma
     * karty (przyszły albo sprzed odbić). {@code timeOff}: minuty czasu wolnego za nadgodziny tego dnia, a
     * {@code compensates}: ile minut nadgodzin nimi rozliczono (art. 151²: na wniosek 1:1, z inicjatywy pracodawcy
     * 1,5:1).
     */
    public record Day(LocalDate date, double norm, Integer planned, Integer actual, int dailyLimit, int timeOff,
            int compensates) {

        public Day(LocalDate date, double norm, Integer planned, Integer actual, int dailyLimit) {
            this(date, norm, planned, actual, dailyLimit, 0, 0);
        }
    }

    /**
     * {@code worked}: minuty do salda, z czasem wolnym za nadgodziny, bo liczy się do wymiaru jak praca (art. 151² § 3:
     * nie obniża wynagrodzenia za pełny wymiar); {@code dailyOvertime}: minuty z kart ponad limit dobowy, poza saldem;
     * {@code timeOff}: czas wolny za nadgodziny; {@code compensated}: nadgodziny nim rozliczone.
     */
    public record Sum(int worked, int norm, int dailyOvertime, int timeOff, int compensated) {

        public Sum(int worked, int norm, int dailyOvertime) {
            this(worked, norm, dailyOvertime, 0, 0);
        }

        /** Ile ponad wymiar (na minusie: ile brakuje). */
        public int balance() {
            return worked - norm;
        }

        /** Nadgodziny dobowe bez czasu wolnego: do odebrania albo zapłaty; na minusie czasu wolnego dano za dużo. */
        public int toCompensate() {
            return dailyOvertime - compensated;
        }
    }

    public static Sum sum(List<Day> days) {
        int worked = 0;
        int overtime = 0;
        int timeOff = 0;
        int compensated = 0;
        double norm = 0;
        for (Day day : days) {
            norm += day.norm();
            timeOff += day.timeOff();
            compensated += day.compensates();
            worked += day.timeOff();
            if (day.actual() != null) {
                int over = Math.max(0, day.actual() - day.dailyLimit());
                overtime += over;
                worked += day.actual() - over;
            } else if (day.planned() != null) {
                worked += day.planned();
            }
        }
        return new Sum(worked, (int) Math.round(norm), overtime, timeOff, compensated);
    }

    /**
     * Saldo przeniesione z wcześniejszych miesięcy okresu. Miesiące zakończone ({@code closed}) przenoszą całe saldo:
     * nadwyżka i niedobór są już faktem. Miesiąc w toku i przyszłe ({@code open}) tylko nadwyżkę: niedobór da się jeszcze
     * nadrobić w nich samych, a pusty grafik to dni do ułożenia, nie niedobór. {@code null}: brak takich miesięcy.
     */
    public static int carried(Sum closed, Sum open) {
        return (closed == null ? 0 : closed.balance()) + excess(open);
    }

    /**
     * Cel miesiąca: wymiar miesiąca minus saldo przeniesione ({@link #carried}; może wyjść ujemny, gdy nadwyżki nie da
     * się już wyrównać) i minus nadwyżka zaplanowana w późniejszych miesiącach. Niedobór późniejszych miesięcy nie
     * podnosi celu, bo nieułożony grafik nie może zrzucić ich wymiaru na ten miesiąc.
     */
    public static int monthTarget(Sum month, int carried, Sum later) {
        return month.norm() - carried - excess(later);
    }

    private static int excess(Sum sum) {
        return sum == null ? 0 : Math.max(0, sum.balance());
    }
}
