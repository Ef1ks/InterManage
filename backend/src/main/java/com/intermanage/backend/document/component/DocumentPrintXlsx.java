package com.intermanage.backend.document.component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.component.XlsxWriter.Cell;
import com.intermanage.backend.common.component.XlsxWriter.Column;
import com.intermanage.backend.common.component.XlsxWriter.Row;
import com.intermanage.backend.document.component.DocumentPrint.Heading;
import com.intermanage.backend.document.component.DocumentPrint.Line;
import com.intermanage.backend.document.component.DocumentPrint.NomenclatureRow;
import com.intermanage.backend.document.component.DocumentPrint.Party;
import com.intermanage.backend.document.component.DocumentPrint.Totals;
import com.intermanage.backend.document.component.DocumentPrint.VatRow;

/**
 * Dokument magazynowy w Excelu: nad tabelą opis dokumentu, potem linie i te same podsumowania co na wydruku PDF. Kwoty
 * podsumowań stoją w kolumnach kwot z linii, więc arkusz czyta się z góry na dół.
 */
@Component
public class DocumentPrintXlsx {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final List<Column> COLUMNS = List.of(new Column("Lp.", 6), new Column("ITM8", 15),
            new Column("EAN", 15), new Column("Nazwa", 42), new Column("Marka", 16), new Column("Dostawca", 28),
            new Column("Nomenklatura", 13), new Column("Ilość", 10), new Column("Jednostka", 10),
            new Column("VAT %", 7), new Column("Cena zakupu", 12), new Column("Zakup netto", 13),
            new Column("Cena sprzedaży", 14), new Column("Sprzedaż netto", 14), new Column("VAT od sprzedaży", 15),
            new Column("Sprzedaż brutto", 15), new Column("Uwagi", 24));

    private final XlsxWriter writer;

    public DocumentPrintXlsx(XlsxWriter writer) {
        this.writer = writer;
    }

    public byte[] xlsx(DocumentPrint print) {
        List<Row> rows = new ArrayList<>();
        print.lines().forEach(line -> rows.add(line(line)));
        Totals totals = print.totals();
        rows.add(summary(Cell.bold("Razem"), null, null, totals.purchaseValue(),
                totals.saleValueNet(), totals.saleVat(), totals.saleValueGross(), true));
        if (print.skippedLines() > 0) {
            rows.add(Row.of(null, null, null, "Pominięto pozycje z ilością 0: " + print.skippedLines()));
        }

        rows.add(Row.of());
        rows.add(Row.of(null, Cell.bold("Kod"), null, Cell.bold("Podsumowanie według nomenklatury"), null, null, null,
                Cell.bold("Pozycji"), null, null, null, Cell.bold("Zakup netto"), null, Cell.bold("Sprzedaż netto"), null,
                Cell.bold("Sprzedaż brutto"), Cell.bold("Udział %")));
        print.nomenclature().forEach(row -> rows.add(nomenclature(row)));

        rows.add(Row.of());
        rows.add(Row.of(null, null, null, Cell.bold("Podsumowanie według stawek VAT"), null, null, null, null, null,
                null, Cell.bold("VAT od zakupu"), Cell.bold("Zakup netto"), null, Cell.bold("Sprzedaż netto"),
                Cell.bold("VAT od sprzedaży"), Cell.bold("Sprzedaż brutto")));
        print.vatRates().forEach(row -> rows.add(Row.of(null, null, null, row.rate() != null ? row.rate() + "%" : "–",
                null, null, null, null, null, null, Cell.money(row.purchaseVat()), Cell.money(row.purchaseNet()), null,
                Cell.money(row.saleNet()), Cell.money(row.saleVat()), Cell.money(row.saleGross()))));
        rows.add(Row.of(null, null, null, Cell.bold("Razem"), null, null, null, null, null, null,
                Cell.boldMoney(totals.purchaseVat()), Cell.boldMoney(totals.purchaseValue()), null,
                Cell.boldMoney(totals.saleValueNet()), Cell.boldMoney(totals.saleVat()),
                Cell.boldMoney(totals.saleValueGross())));

        return writer.writeRows(print.heading().label(), intro(print.heading()), COLUMNS, rows);
    }

    /** Opis dokumentu nad tabelą; pusty wiersz oddziela go od nagłówka kolumn. */
    private static List<Row> intro(Heading heading) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(Cell.bold(heading.title() + " nr " + heading.number())));
        rows.add(Row.of("Data dokumentu: " + DATE.format(heading.date())));
        rows.add(Row.of("Sklep: " + describe(heading.store())));
        Party party = heading.party();
        if (party != null && party.name() != null) {
            rows.add(Row.of(party.label() + ": " + describe(party)));
        }
        heading.details().forEach(detail -> rows.add(Row.of(detail.label() + ": " + detail.value())));
        rows.add(Row.of());
        return rows;
    }

    private static String describe(Party party) {
        return Stream.concat(Stream.of(party.name()), party.lines().stream()).collect(Collectors.joining(", "));
    }

    private static Row line(Line line) {
        return Row.of(BigDecimal.valueOf(line.lineNumber()), line.itm8(), line.ean(), line.name(), line.brand(),
                line.supplier(), line.nomenclatureCode(), line.quantity(), line.unit(),
                line.vatRate() != null ? BigDecimal.valueOf(line.vatRate()) : null, line.purchasePrice(),
                money(line.purchaseValue()), line.salePrice(), money(line.saleValueNet()), money(line.vatValue()),
                money(line.saleValueGross()), line.note());
    }

    private static Row nomenclature(NomenclatureRow row) {
        Object code = row.code().isEmpty() ? null : row.region() ? Cell.bold(row.code()) : row.code();
        Object name = row.region() ? Cell.bold(row.name()) : "    " + row.name();
        Row summary = summary(code, name, BigDecimal.valueOf(row.lineCount()), row.purchaseValue(), row.saleValueNet(),
                null, row.saleValueGross(), row.region());
        List<Object> cells = new ArrayList<>(summary.cells());
        cells.add(row.share());
        return new Row(cells, 0);
    }

    /** Wiersz w układzie kolumn z liniami: kod, nazwa, liczba pozycji i kwoty pod swoimi nagłówkami. */
    private static Row summary(Object code, Object name, BigDecimal count, BigDecimal purchase, BigDecimal saleNet,
            BigDecimal saleVat, BigDecimal saleGross, boolean bold) {
        return Row.of(null, code, null, name, null, null, null, count, null, null, null,
                amount(purchase, bold), null, amount(saleNet, bold), amount(saleVat, bold), amount(saleGross, bold));
    }

    private static Cell amount(BigDecimal value, boolean bold) {
        return value == null ? null : bold ? Cell.boldMoney(value) : Cell.money(value);
    }

    private static Cell money(BigDecimal value) {
        return value == null ? null : Cell.money(value);
    }
}
