package com.intermanage.backend.dataentry.component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Plik XML faktury dla programu sklepu (sztywny schemat z plików dostawców): {@code DOKUMENT} z {@code NAGLOWEK}
 * (numer, data RRRR.MM.DD, dostawca, odbiorca = numer sklepu, liczba pozycji, wartość netto, NIP dostawcy) i
 * {@code POZYCJE}. Pozycja to dostawa ({@code OPERACJA} D) z EAN-em (bez niego 13 zer, jak gratisy u dostawców),
 * ceną netto, stawką VAT i ilością; rabat zawsze 0, PKWiU puste. Kodowanie windows-1250, wcięcia tabulatorem, CRLF.
 * {@link #read} czyta taki plik z powrotem (własny albo od dostawcy).
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

    /** Odczytany plik: {@code netValue} = WART_NET z nagłówka ({@code null} bez niej). */
    public record Document(Header header, BigDecimal netValue, List<Line> lines) {
    }

    /** Plik, który nie jest XML-em faktury w tym schemacie. */
    public static class InvalidXmlException extends RuntimeException {
        InvalidXmlException(String message) {
            super(message);
        }

        InvalidXmlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private InvoiceXml() {
    }

    /**
     * Plik XML faktury w schemacie z {@link #write}: kodowanie z deklaracji pliku, liczby z kropką albo przecinkiem,
     * data RRRR.MM.DD (albo RRRR-MM-DD), EAN z 13 zerami albo pusty = brak EAN-u. Bez DTD i encji zewnętrznych (XXE).
     */
    public static Document read(byte[] xml) {
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new InvalidXmlException("Plik nie jest poprawnym plikiem XML", e);
        }
        if (!"DOKUMENT".equals(root.getTagName())) {
            throw new InvalidXmlException("To nie jest plik XML faktury: brak elementu DOKUMENT");
        }
        Element header = child(root, "NAGLOWEK");
        Element positions = child(root, "POZYCJE");
        if (header == null || positions == null) {
            throw new InvalidXmlException("To nie jest plik XML faktury: brak nagłówka albo pozycji");
        }
        List<Line> lines = new ArrayList<>();
        for (Node node = positions.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element position && "POZYCJA".equals(position.getTagName())) {
                int number = lines.size() + 1;
                String ean = text(position, "KOD_KRESKOWY");
                lines.add(new Line(text(position, "NAZWA"),
                        ean == null || ean.chars().allMatch(c -> c == '0') ? null : ean,
                        number(position, "CENA_NET", number), number(position, "PROC_VAT", number),
                        number(position, "ILOSC", number)));
            }
        }
        return new Document(new Header(text(header, "NR_DOKUMENTU"), date(text(header, "DATA_DOKUMENTU")),
                text(header, "KOD_DOSTAWCY"), text(header, "KOD_ODBIORCY"), text(header, "NIP_DOSTAWCY")),
                number(header, "WART_NET", 0), lines);
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        String value = element == null ? null : element.getTextContent().strip();
        return value == null || value.isEmpty() ? null : value;
    }

    /** {@code line} 0 = nagłówek (do komunikatu błędu). */
    private static BigDecimal number(Element parent, String name, int line) {
        String value = text(parent, name);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(',', '.').replace(" ", ""));
        } catch (NumberFormatException e) {
            throw new InvalidXmlException((line == 0 ? "Nagłówek" : "Pozycja " + line) + ": " + name
                    + " nie jest liczbą (" + value + ")");
        }
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.replace('-', '.').substring(0, Math.min(value.length(), 10)), DATE);
        } catch (DateTimeParseException e) {
            throw new InvalidXmlException("DATA_DOKUMENTU nie jest datą (" + value + ")");
        }
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

    /**
     * Litery cyrylicy i greki wyglądające jak łacińskie (dostawcy wpisują je w numery faktur w KSeF, np.
     * „FAN/ZА664” z cyrylickim А) zamienione na łacińskie, bo windows-1250 ich nie ma i zostałby „?”.
     */
    private static final String LOOKALIKES_FROM =
            "АВЕЅІЈКМНОРСТХУаеѕіјорсхуԁһӏΑΒΕΖΗΙΚΜΝΟΡΤΥΧον";
    private static final String LOOKALIKES_TO =
            "ABESIJKMHOPCTXYaesijopcxydhlABEZHIKMNOPTYXov";

    public static String latin(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            int index = LOOKALIKES_FROM.indexOf(c);
            out.append(index >= 0 ? LOOKALIKES_TO.charAt(index) : c);
        }
        return out.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : latin(value.strip()).toCharArray()) {
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
