package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.component.XlsxWriter.Cell;
import com.intermanage.backend.common.component.XlsxWriter.Column;
import com.intermanage.backend.common.component.XlsxWriter.Row;
import com.intermanage.backend.sales.dto.BasketFileRequest;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;

/**
 * Zamówienie z koszyka porównania sprzedaży dla dostawców spoza bazy: arkusz z artykułami, ilością i ceną zakupu
 * netto. Kilku dostawców idzie jednym arkuszem, każdy w swojej sekcji z nagłówkiem i sumą, żeby dało się go wyciąć.
 */
@Service
public class BasketFileService {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final List<Column> COLUMNS = List.of(
            new Column("ITM8", 14),
            new Column("EAN", 16),
            new Column("Nazwa", 40),
            new Column("Marka", 18),
            new Column("Ilość", 10),
            new Column("Cena zakupu netto", 18),
            new Column("Wartość netto", 16));

    private final XlsxWriter xlsxWriter;

    public BasketFileService(XlsxWriter xlsxWriter) {
        this.xlsxWriter = xlsxWriter;
    }

    public ExportFile xlsx(BasketFileRequest request) {
        List<BasketFileRequest.Supplier> suppliers = request.suppliers();
        boolean single = suppliers.size() == 1;
        List<Row> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (BasketFileRequest.Supplier supplier : suppliers) {
            if (!single) {
                if (!rows.isEmpty()) rows.add(Row.of());
                rows.add(Row.of(Cell.bold(supplierLabel(supplier))));
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (BasketFileRequest.Line line : supplier.lines()) {
                BigDecimal value = line.purchasePrice() == null ? null
                        : line.purchasePrice().multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP);
                if (value != null) sum = sum.add(value);
                rows.add(Row.of(line.itm8(), blankToNull(line.ean()), blankToNull(line.name()),
                        blankToNull(line.brand()), line.quantity(),
                        line.purchasePrice() == null ? null : Cell.money(line.purchasePrice()),
                        value == null ? null : Cell.money(value)));
            }
            rows.add(Row.of(Cell.bold(single ? "Razem" : "Razem " + supplier.name()), null, null, null, null, null,
                    Cell.boldMoney(sum)));
            total = total.add(sum);
        }
        if (!single) {
            rows.add(Row.of());
            rows.add(Row.of(Cell.bold("Razem wszyscy dostawcy"), null, null, null, null, null,
                    Cell.boldMoney(total)));
        }
        List<Row> intro = single
                ? List.of(Row.of(Cell.bold("Zamówienie: " + supplierLabel(suppliers.getFirst()))), Row.of())
                : List.of(Row.of(Cell.bold("Zamówienie dla " + suppliers.size() + " dostawców")), Row.of());
        String stamp = LocalDateTime.now(StoreTime.ZONE).format(STAMP);
        String fileName = "zamowienie_" + (single ? namePart(suppliers.getFirst().name()) : "dostawcy") + "_"
                + stamp + ".xlsx";
        return new ExportFile(fileName, XLSX, xlsxWriter.writeRows("Zamówienie", intro, COLUMNS, rows));
    }

    private static String supplierLabel(BasketFileRequest.Supplier supplier) {
        return supplier.code() == null || supplier.code().isBlank() ? supplier.name()
                : supplier.code() + " " + supplier.name();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** Nazwa dostawcy w nazwie pliku: bez polskich znaków, same litery i cyfry, do 40 znaków. */
    static String namePart(String name) {
        String plain = Normalizer.normalize(name.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (plain.isEmpty()) return "DOSTAWCA";
        return plain.length() > 40 ? plain.substring(0, 40) : plain;
    }
}
