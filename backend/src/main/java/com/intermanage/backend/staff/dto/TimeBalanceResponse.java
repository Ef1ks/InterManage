package com.intermanage.backend.staff.dto;

import java.time.LocalDate;

/**
 * Saldo okresu rozliczeniowego pracownika (jak w grafiku): za dni, które minęły, czas z kart, dalej grafik.
 * {@code normMinutes} pusty bez umowy o pracę w okresie. {@code toCompensateMinutes}: nadgodziny dobowe bez czasu
 * wolnego. {@code dailyLimitMinutes}: limit dobowy w dniu decyzji (8 h, przy znacznym stopniu bez zgody lekarza 7 h).
 */
public record TimeBalanceResponse(LocalDate periodStart, LocalDate periodEnd, int workedMinutes, Integer normMinutes,
        int dailyOvertimeMinutes, int toCompensateMinutes, LocalDate actualUntil, int dailyLimitMinutes) {
}
