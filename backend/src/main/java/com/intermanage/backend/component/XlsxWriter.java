package com.intermanage.backend.component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

/**
 * Najprostszy skoroszyt .xlsx z jednym arkuszem: pogrubiony, przypięty nagłówek i wiersze danych. Teksty zapisujemy
 * jako tekst (EAN zachowuje zera wiodące), {@link BigDecimal} jako liczby, żeby w Excelu dało się je sumować.
 * Komórka {@link Cell} może być pogrubiona albo mieć format kwoty, a {@link Row} z poziomem 1 zwija się pod wierszem
 * nad nim (grupowanie w Excelu).
 */
@Component
public class XlsxWriter {

    /** Kolumna arkusza: nagłówek i szerokość w znakach. */
    public record Column(String header, int width) {
    }

    /** Komórka z formatem: pogrubiona i/lub kwota z dwoma miejscami po przecinku. */
    public record Cell(Object value, boolean bold, boolean money) {

        public static Cell bold(Object value) {
            return new Cell(value, true, false);
        }

        public static Cell money(BigDecimal value) {
            return new Cell(value, false, true);
        }

        public static Cell boldMoney(BigDecimal value) {
            return new Cell(value, true, true);
        }
    }

    /** Wiersz arkusza; {@code outlineLevel} 1 = wiersz zgrupowany pod wierszem nad nim (da się go zwinąć). */
    public record Row(List<Object> cells, int outlineLevel) {

        /** Wiersz bez grupowania; {@code null} to pusta komórka. */
        public static Row of(Object... cells) {
            return new Row(Arrays.asList(cells), 0);
        }

        public static Row nested(Object... cells) {
            return new Row(Arrays.asList(cells), 1);
        }
    }

    public byte[] write(String sheetName, List<Column> columns, List<List<Object>> rows) {
        return writeRows(sheetName, columns, rows.stream().map(cells -> new Row(cells, 0)).toList());
    }

    public byte[] writeRows(String sheetName, List<Column> columns, List<Row> rows) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>""".formatted(escape(sheetName)));
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>""");
            // Styl 0: zwykły, 1: pogrubiony (też nagłówek), 2: kwota (#,##0.00), 3: pogrubiona kwota.
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                    <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                    <cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="4" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/></cellXfs>
                    </styleSheet>""");
            put(zip, "xl/worksheets/sheet1.xml", sheet(columns, rows));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String sheet(List<Column> columns, List<Row> rows) {
        boolean outlined = rows.stream().anyMatch(row -> row.outlineLevel() > 0);
        // Przycisk zwijania grupy przy wierszu nad nią; nagłówek zostaje na górze przy przewijaniu.
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                <sheetPr><outlinePr summaryBelow="0"/></sheetPr>\
                <sheetViews><sheetView workbookViewId="0">\
                <pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""");
        if (outlined) {
            xml.append("<sheetFormatPr defaultRowHeight=\"15\" outlineLevelRow=\"1\"/>");
        }
        xml.append("<cols>");
        for (int c = 0; c < columns.size(); c++) {
            xml.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                    .append(columns.get(c).width()).append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols><sheetData><row r=\"1\">");
        for (int c = 0; c < columns.size(); c++) {
            xml.append(textCell(ref(c, 1), columns.get(c).header(), 1));
        }
        xml.append("</row>");
        for (int r = 0; r < rows.size(); r++) {
            int rowNumber = r + 2;
            Row row = rows.get(r);
            xml.append("<row r=\"").append(rowNumber).append('"');
            if (row.outlineLevel() > 0) {
                xml.append(" outlineLevel=\"").append(row.outlineLevel()).append('"');
            }
            xml.append('>');
            for (int c = 0; c < row.cells().size(); c++) {
                Object value = row.cells().get(c);
                int style = 0;
                if (value instanceof Cell cell) {
                    style = (cell.bold() ? 1 : 0) + (cell.money() ? 2 : 0);
                    value = cell.value();
                }
                if (value instanceof BigDecimal number) {
                    xml.append("<c r=\"").append(ref(c, rowNumber)).append('"')
                            .append(style > 0 ? " s=\"" + style + "\"" : "").append("><v>")
                            .append(number.stripTrailingZeros().toPlainString()).append("</v></c>");
                } else if (value != null) {
                    xml.append(textCell(ref(c, rowNumber), value.toString(), style & 1));
                }
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private static String textCell(String ref, String text, int style) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"" + (style > 0 ? " s=\"" + style + "\"" : "") + "><is><t>"
                + escape(text) + "</t></is></c>";
    }

    /** Kolumna 0 = A; wystarcza do 26 kolumn. */
    private static String ref(int column, int row) {
        return (char) ('A' + column) + String.valueOf(row);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
