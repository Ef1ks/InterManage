package com.intermanage.backend.dataentry.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Plik XML faktury dla programu sklepu (sztywny schemat z plików dostawców): {@code DOKUMENT} z {@code NAGLOWEK}
 * (numer, data RRRR.MM.DD, dostawca, odbiorca = numer sklepu, liczba pozycji, wartość netto, NIP dostawcy) i
 * {@code POZYCJE}. Pozycja to dostawa ({@code OPERACJA} D) z EAN-em (bez niego 13 zer, jak gratisy u dostawców),
 * ceną netto, stawką VAT i ilością; rabat zawsze 0, PKWiU puste. Kodowanie windows-1250, wcięcia tabulatorem, CRLF.
 */
public final class InvoiceXml {

    public static final Charset CHARSET = Charset.forName("windows-1250");
    public static final String NO_EAN = "0000000000000";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String NL = "\r\n";

    public record Header(String documentNumber, LocalDate date, String supplierCode, String recipientCode,
            String supplierNip) {
    }

    /** {@code ean} {@code null} = brak EAN-u; {@code vatPercent} {@code null} = pusta stawka. */
    public record Line(String name, String ean, BigDecimal unitNet, BigDecimal vatPercent, BigDecimal quantity) {
    }

    private InvoiceXml() {
    }

    /** Cena netto w pliku: 2 miejsca, jak u dostawców. */
    public static BigDecimal price(BigDecimal unitNet) {
        return (unitNet == null ? BigDecimal.ZERO : unitNet).setScale(2, RoundingMode.HALF_UP);
    }

    /** Wartość netto nagłówka: suma ilość × cena z pliku. */
    public static BigDecimal netValue(List<Line> lines) {
        return lines.stream()
                .map(line -> price(line.unitNet()).multiply(line.quantity() == null ? BigDecimal.ZERO : line.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    public static byte[] write(Header header, List<Line> lines) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"windows-1250\"?>").append(NL);
        xml.append("<DOKUMENT>").append(NL);
        xml.append("\t<NAGLOWEK>").append(NL);
        element(xml, 2, "NR_DOKUMENTU", header.documentNumber());
        element(xml, 2, "DATA_DOKUMENTU", header.date().format(DATE));
        element(xml, 2, "KOD_DOSTAWCY", header.supplierCode());
        element(xml, 2, "KOD_ODBIORCY", header.recipientCode());
        element(xml, 2, "ILOSC_POZYCJI", String.valueOf(lines.size()));
        element(xml, 2, "WART_NET", netValue(lines).toPlainString());
        element(xml, 2, "NIP_DOSTAWCY", header.supplierNip());
        xml.append("\t</NAGLOWEK>").append(NL);
        xml.append("\t<POZYCJE>").append(NL);
        int number = 0;
        for (Line line : lines) {
            xml.append("\t\t<POZYCJA>").append(NL);
            element(xml, 3, "OPERACJA", "D");
            element(xml, 3, "LP", String.valueOf(++number));
            element(xml, 3, "NAZWA", line.name());
            element(xml, 3, "KOD_KRESKOWY", line.ean() == null || line.ean().isBlank() ? NO_EAN : line.ean());
            element(xml, 3, "CENA_NET", price(line.unitNet()).toPlainString());
            element(xml, 3, "PROC_RABAT", "0.00");
            element(xml, 3, "PROC_VAT", line.vatPercent() == null ? null
                    : line.vatPercent().setScale(2, RoundingMode.HALF_UP).toPlainString());
            element(xml, 3, "PKWiU", null);
            element(xml, 3, "ILOSC", (line.quantity() == null ? BigDecimal.ZERO : line.quantity())
                    .setScale(3, RoundingMode.HALF_UP).toPlainString());
            xml.append("\t\t</POZYCJA>").append(NL);
        }
        xml.append("\t</POZYCJE>").append(NL);
        xml.append("</DOKUMENT>").append(NL);
        return encode(xml.toString());
    }

    private static void element(StringBuilder xml, int depth, String name, String value) {
        xml.append("\t".repeat(depth));
        if (value == null || value.isEmpty()) {
            xml.append('<').append(name).append(" />");
        } else {
            xml.append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>');
        }
        xml.append(NL);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.strip().toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(Character.isISOControl(c) ? ' ' : c);
            }
        }
        return out.toString();
    }

    /** Znaki spoza windows-1250 (np. emoji) jako "?". */
    private static byte[] encode(String text) {
        CharsetEncoder encoder = CHARSET.newEncoder().onUnmappableCharacter(CodingErrorAction.REPLACE)
                .onMalformedInput(CodingErrorAction.REPLACE).replaceWith(new byte[] {'?'});
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
