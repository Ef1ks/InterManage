package com.intermanage.backend.common.component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.exception.InvalidWorkbookException;

/**
 * Arkusz zapisany tekstem z końcówką .xls: systemy webowe eksportują tak tabelę HTML, XML arkusza Excela 2003
 * (SpreadsheetML) albo tekst z tabulatorami. Excel otwiera je z ostrzeżeniem „inny format niż rozszerzenie”. Wynik jak
 * w {@link XlsReader}: wiersze pierwszej tabeli / arkusza, teksty jak w pliku, pusta komórka to {@code null}.
 */
@Component
public class TextSheetReader {

    private static final Pattern CHARSET = Pattern.compile("(?:charset|encoding)\\s*=\\s*[\"']?([\\w-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE = Pattern.compile("<table\\b.*?</table\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>(.*?)(?=<tr\\b|</table|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t([dh])\\b([^>]*)>(.*?)(?=<t[dh]\\b|</tr|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COLSPAN = Pattern.compile("colspan\\s*=\\s*[\"']?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHEET_SOURCE = Pattern.compile(
            "(?:WorksheetSource\\s+HRef|frame\\s+src)\\s*=\\s*\"([^\"]*sheet\\d+\\.html?)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

    /** Czy pierwsze bajty wyglądają na tekst (bez bajtów sterujących poza tabulatorem i końcem linii). */
    public static boolean looksLikeText(byte[] bytes) {
        int start = bomLength(bytes);
        if (start == 2) {
            return true;
        }
        int length = Math.min(bytes.length, start + 512);
        for (int i = start; i < length; i++) {
            int b = bytes[i] & 0xFF;
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                return false;
            }
        }
        return length > start;
    }

    public List<List<String>> readFirstSheet(byte[] file) throws InvalidWorkbookException {
        String text = decode(file);
        String lower = text.toLowerCase(Locale.ROOT);
        try {
            if (lower.contains("urn:schemas-microsoft-com:office:spreadsheet") && lower.contains("<worksheet")) {
                return spreadsheetMl(text);
            }
            // „Strona sieci Web” z Excela: ten plik to tylko ramka, arkusz leży obok w folderze <nazwa>_pliki.
            Matcher frame = SHEET_SOURCE.matcher(text);
            if (!lower.contains("<td") && (lower.contains("<frameset") || frame.find())) {
                String sheet = frame.find(0) ? frame.group(1) : "sheet001.htm";
                throw new InvalidWorkbookException("Ten plik to tylko ramka strony zapisanej przez Excela, bez danych. "
                        + "Dane są w osobnym pliku " + sheet + ": wgraj właśnie ten plik.");
            }
            if (lower.contains("<table")) {
                return html(text);
            }
            if (lower.stripLeading().startsWith("<")) {
                throw new InvalidWorkbookException("Plik jest stroną HTML bez tabeli z danymi");
            }
            return delimited(text);
        } catch (XMLStreamException | RuntimeException e) {
            throw new InvalidWorkbookException("Nie udało się odczytać pliku: " + e.getMessage(), e);
        }
    }

    // ---------- kodowanie

    private static int bomLength(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return 3;
        }
        if (b.length >= 2 && ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE
                || (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)) {
            return 2;
        }
        return 0;
    }

    /** BOM, potem deklaracja z pliku ({@code charset=} w meta, {@code encoding=} w XML), potem UTF-8, potem windows-1250. */
    private static String decode(byte[] b) {
        int bom = bomLength(b);
        if (bom == 3) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (bom == 2) {
            Charset utf16 = (b[0] & 0xFF) == 0xFF ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
            return new String(b, 2, b.length - 2, utf16);
        }
        String head = new String(b, 0, Math.min(b.length, 4096), StandardCharsets.ISO_8859_1);
        Matcher declared = CHARSET.matcher(head);
        if (declared.find()) {
            try {
                return new String(b, Charset.forName(declared.group(1)));
            } catch (IllegalArgumentException ignored) {
                // nieznana nazwa kodowania, zgadujemy niżej
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1250"));
        }
    }

    // ---------- tabela HTML

    private static List<List<String>> html(String text) {
        Matcher table = TABLE.matcher(text);
        String body = table.find() ? table.group() : text.substring(text.toLowerCase(Locale.ROOT).indexOf("<table"));
        List<List<String>> rows = new ArrayList<>();
        Matcher row = ROW.matcher(body);
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                cells.add(cellValue(htmlText(cell.group(3))));
                Matcher span = COLSPAN.matcher(cell.group(2));
                int extra = span.find() ? Math.min(Integer.parseInt(span.group(1)), 1000) - 1 : 0;
                for (int i = 0; i < extra; i++) {
                    cells.add(null);
                }
            }
            rows.add(trimmed(cells));
        }
        return rows;
    }

    private static String htmlText(String html) {
        String text = html.replaceAll("(?is)<br\\s*/?>", " ").replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?s)<[^>]*>", "");
        Matcher entity = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (entity.find()) {
            entity.appendReplacement(out, Matcher.quoteReplacement(entity(entity.group(1))));
        }
        entity.appendTail(out);
        return out.toString().replaceAll("\\s+", " ");
    }

    private static String entity(String name) {
        if (name.startsWith("#x") || name.startsWith("#X")) {
            return Character.toString(Integer.parseInt(name.substring(2), 16));
        }
        if (name.startsWith("#")) {
            return Character.toString(Integer.parseInt(name.substring(1)));
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            default -> "&" + name + ";";
        };
    }

    // ---------- XML arkusza Excela 2003

    private static List<List<String>> spreadsheetMl(String text) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader xml = factory.createXMLStreamReader(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "UTF-8");
        List<List<String>> rows = new ArrayList<>();
        List<String> cells = null;
        StringBuilder data = null;
        int worksheets = 0;
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "Worksheet" -> worksheets++;
                        case "Row" -> {
                            if (worksheets == 1) {
                                int index = intAttribute(xml, "Index");
                                while (index > 0 && rows.size() < index - 1) {
                                    rows.add(List.of());
                                }
                                cells = new ArrayList<>();
                            }
                        }
                        case "Cell" -> {
                            if (cells != null) {
                                int index = intAttribute(xml, "Index");
                                while (index > 0 && cells.size() < index - 1) {
                                    cells.add(null);
                                }
                                data = new StringBuilder();
                            }
                        }
                        default -> {
                        }
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && data != null) {
                    data.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "Cell" -> {
                            if (cells != null && data != null) {
                                cells.add(cellValue(data.toString()));
                                data = null;
                            }
                        }
                        case "Row" -> {
                            if (cells != null) {
                                rows.add(trimmed(cells));
                                cells = null;
                            }
                        }
                        case "Worksheet" -> {
                            return rows;
                        }
                        default -> {
                        }
                    }
                }
            }
        } finally {
            xml.close();
        }
        return rows;
    }

    private static int intAttribute(XMLStreamReader xml, String name) {
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            if (name.equals(xml.getAttributeLocalName(i))) {
                return Integer.parseInt(xml.getAttributeValue(i).strip());
            }
        }
        return 0;
    }

    // ---------- tekst z separatorem

    private static List<List<String>> delimited(String text) {
        String[] lines = text.split("\r\n|\n|\r", -1);
        String first = lines.length > 0 ? lines[0] : "";
        char separator = first.indexOf('\t') >= 0 ? '\t' : first.indexOf(';') >= 0 ? ';' : ',';
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"' && (quoted || cell.isEmpty())) {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (c == separator && !quoted) {
                    cells.add(cellValue(cell.toString()));
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            cells.add(cellValue(cell.toString()));
            rows.add(trimmed(cells));
        }
        while (!rows.isEmpty() && rows.getLast().isEmpty()) {
            rows.removeLast();
        }
        return rows;
    }

    // ---------- wspólne

    private static String cellValue(String raw) {
        String value = raw.replace('\u00A0', ' ').strip();
        return value.isEmpty() ? null : value;
    }

    private static List<String> trimmed(List<String> cells) {
        int end = cells.size();
        while (end > 0 && cells.get(end - 1) == null) {
            end--;
        }
        return new ArrayList<>(cells.subList(0, end));
    }
}
