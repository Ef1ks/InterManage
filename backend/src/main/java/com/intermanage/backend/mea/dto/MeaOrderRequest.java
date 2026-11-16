package com.intermanage.backend.mea.dto;

import java.util.List;

/**
 * Zamówienie MEA do ATHOS: kartony (COLI) na rozrzuty R1-R4 po ITM8 MEA. Pozycje formularza, których tu nie ma, idą
 * do pliku z zerami (jak w eMEA, plik ma zawsze wszystkie pozycje).
 */
public record MeaOrderRequest(List<Line> lines) {

    public record Line(String itm8, List<Integer> colis) {
    }
}
