package com.intermanage.backend.common.component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.exception.InvalidWorkbookException;

/**
 * Strumieniowy odczyt pierwszego arkusza pliku .xlsx / .xlsm (Office Open XML) bez ładowania całego arkusza do pamięci.
 * Zwraca surowe wartości komórek: liczby jako zapis z pliku (np. {@code "8.99"}, {@code "0.27500000000000002"}),
 * teksty z tabeli współdzielonej, bez formatowania i bez przeliczania formuł (bierze zapisany wynik).
 * Stary format .xls nie jest obsługiwany.
 */
@Component
public class XlsxReader {

    /** Wywoływany dla każdego wiersza; {@code cells.get(i)} to kolumna i (A = 0), {@code null} gdy komórka pusta. */
    @FunctionalInterface
    public interface RowHandler {
        void row(int rowNumber, List<String> cells);
    }

    /** Ochrona przed "bombą zip": arkusz z 72 tys. produktów ma ok. 66 MB XML. */
    private static final long MAX_ENTRY_BYTES = 1024L * 1024 * 1024;

    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    public void readFirstSheet(Path file, RowHandler handler) throws IOException, InvalidWorkbookException {
        readFirstSheet(file, handler, null);
    }

    /**
     * Jak {@link #readFirstSheet(Path, RowHandler)}, a {@code progress} dostaje przeczytaną część arkusza (0-1, z
     * rozmiaru XML po rozpakowaniu zapisanego w archiwum), co najmniej co 1%.
     */
    public void readFirstSheet(Path file, RowHandler handler, DoubleConsumer progress) throws IOException,
            InvalidWorkbookException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Map<String, String> relationships = readRelationships(zip);
            String sheetPath = sheetPaths(zip, relationships).values().iterator().next();
            List<String> sharedStrings = readSharedStrings(zip, relationships);
            readSheet(zip, sheetPath, sharedStrings, handler, progress);
        } catch (ZipException e) {
            throw new InvalidWorkbookException("Plik nie jest skoroszytem .xlsx", e);
        } catch (XMLStreamException e) {
            throw new InvalidWorkbookException("Uszkodzona zawartość skoroszytu", e);
        }
    }

    /** Nazwy arkuszy w kolejności skoroszytu, także ukrytych. */
    public List<String> sheetNames(Path file) throws IOException, InvalidWorkbookException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return List.copyOf(sheetPaths(zip, readRelationships(zip)).keySet());
        } catch (ZipException e) {
            throw new InvalidWorkbookException("Plik nie jest skoroszytem .xlsx", e);
        } catch (XMLStreamException e) {
            throw new InvalidWorkbookException("Uszkodzona zawartość skoroszytu", e);
        }
    }

    /** Jak {@link #readFirstSheet(Path, RowHandler)}, ale arkusz o tej nazwie ({@link #sheetNames}). */
    public void readSheet(Path file, String name, RowHandler handler) throws IOException, InvalidWorkbookException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Map<String, String> relationships = readRelationships(zip);
            String path = sheetPaths(zip, relationships).get(name);
            if (path == null) {
                throw new InvalidWorkbookException("Skoroszyt nie ma arkusza " + name, null);
            }
            readSheet(zip, path, readSharedStrings(zip, relationships), handler, null);
        } catch (ZipException e) {
            throw new InvalidWorkbookException("Plik nie jest skoroszytem .xlsx", e);
        } catch (XMLStreamException e) {
            throw new InvalidWorkbookException("Uszkodzona zawartość skoroszytu", e);
        }
    }

    /** Chwila zapisu skoroszytu z jego właściwości ({@code dcterms:modified}); {@code null}, gdy plik jej nie ma. */
    public Instant modifiedAt(Path file) throws IOException, InvalidWorkbookException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("docProps/core.xml") == null) {
                return null;
            }
            XMLStreamReader xml = open(zip, "docProps/core.xml");
            try {
                while (xml.hasNext()) {
                    if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("modified")) {
                        return Instant.parse(xml.getElementText().strip());
                    }
                }
            } finally {
                xml.close();
            }
            return null;
        } catch (ZipException e) {
            throw new InvalidWorkbookException("Plik nie jest skoroszytem .xlsx", e);
        } catch (XMLStreamException | DateTimeParseException e) {
            // Właściwości pliku są tylko dodatkiem do treści.
            return null;
        }
    }

    /** Relacje skoroszytu: identyfikator ({@code rId1}) i typ ({@code type:...sharedStrings}) -> ścieżka w archiwum. */
    private static Map<String, String> readRelationships(ZipFile zip) throws IOException, XMLStreamException,
            InvalidWorkbookException {
        Map<String, String> result = new HashMap<>();
        XMLStreamReader xml = open(zip, "xl/_rels/workbook.xml.rels");
        try {
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("Relationship")) {
                    String target = xml.getAttributeValue(null, "Target");
                    String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    result.put(xml.getAttributeValue(null, "Id"), path);
                    result.putIfAbsent("type:" + xml.getAttributeValue(null, "Type"), path);
                }
            }
        } finally {
            xml.close();
        }
        return result;
    }

    /** Arkusze w kolejności skoroszytu: nazwa -> ścieżka w archiwum. */
    private static Map<String, String> sheetPaths(ZipFile zip, Map<String, String> relationships) throws IOException,
            XMLStreamException, InvalidWorkbookException {
        Map<String, String> result = new LinkedHashMap<>();
        XMLStreamReader xml = open(zip, "xl/workbook.xml");
        try {
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("sheet")) {
                    String path = relationships.get(xml.getAttributeValue(REL_NS, "id"));
                    if (path != null) {
                        result.putIfAbsent(String.valueOf(xml.getAttributeValue(null, "name")), path);
                    } else if (result.isEmpty()) {
                        break;
                    }
                }
            }
        } finally {
            xml.close();
        }
        if (result.isEmpty()) {
            throw new InvalidWorkbookException("Skoroszyt nie zawiera arkuszy", null);
        }
        return result;
    }

    private static List<String> readSharedStrings(ZipFile zip, Map<String, String> relationships) throws IOException,
            XMLStreamException, InvalidWorkbookException {
        String path = relationships.getOrDefault(
                "type:" + REL_NS + "/sharedStrings", "xl/sharedStrings.xml");
        List<String> strings = new ArrayList<>();
        if (zip.getEntry(path) == null) {
            return strings;
        }
        XMLStreamReader xml = open(zip, path);
        try {
            StringBuilder text = null;
            // Fonetyczne podpowiedzi (<rPh>) też mają <t>, ale nie są częścią tekstu.
            boolean inPhonetic = false;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "si" -> text = new StringBuilder();
                        case "rPh" -> inPhonetic = true;
                        case "t" -> {
                            String value = xml.getElementText();
                            if (text != null && !inPhonetic) {
                                text.append(value);
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (xml.getLocalName().equals("rPh")) {
                        inPhonetic = false;
                    } else if (xml.getLocalName().equals("si") && text != null) {
                        strings.add(text.toString());
                        text = null;
                    }
                }
            }
        } finally {
            xml.close();
        }
        return strings;
    }

    private static void readSheet(ZipFile zip, String path, List<String> sharedStrings, RowHandler handler,
            DoubleConsumer progress) throws IOException, XMLStreamException, InvalidWorkbookException {
        XMLStreamReader xml = open(zip, path, progress);
        try {
            List<String> cells = new ArrayList<>();
            int rowNumber = 0;
            int column = -1;
            String type = null;
            String value = null;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "row" -> {
                            String r = xml.getAttributeValue(null, "r");
                            rowNumber = r != null ? Integer.parseInt(r) : rowNumber + 1;
                            cells.clear();
                            column = -1;
                        }
                        case "c" -> {
                            String ref = xml.getAttributeValue(null, "r");
                            column = ref != null ? columnIndex(ref) : column + 1;
                            type = xml.getAttributeValue(null, "t");
                            value = null;
                        }
                        // <v> - wartość lub indeks tekstu współdzielonego; <t> wewnątrz <is> - tekst wpisany w komórkę.
                        case "v", "t" -> value = xml.getElementText();
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (xml.getLocalName().equals("c")) {
                        while (cells.size() <= column) {
                            cells.add(null);
                        }
                        cells.set(column, cellValue(type, value, sharedStrings));
                    } else if (xml.getLocalName().equals("row")) {
                        handler.row(rowNumber, Collections.unmodifiableList(new ArrayList<>(cells)));
                    }
                }
            }
        } finally {
            xml.close();
        }
    }

    private static String cellValue(String type, String value, List<String> sharedStrings)
            throws InvalidWorkbookException {
        if (value == null) {
            return null;
        }
        if ("s".equals(type)) {
            int index = Integer.parseInt(value.trim());
            if (index < 0 || index >= sharedStrings.size()) {
                throw new InvalidWorkbookException("Komórka wskazuje nieistniejący tekst współdzielony", null);
            }
            return sharedStrings.get(index);
        }
        return value;
    }

    /** "AB12" -> 27. */
    static int columnIndex(String ref) {
        int index = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            index = index * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        }
        return index - 1;
    }

    private static XMLStreamReader open(ZipFile zip, String path) throws IOException, XMLStreamException,
            InvalidWorkbookException {
        return open(zip, path, null);
    }

    private static XMLStreamReader open(ZipFile zip, String path, DoubleConsumer progress) throws IOException,
            XMLStreamException, InvalidWorkbookException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            throw new InvalidWorkbookException("Brak części skoroszytu: " + path, null);
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory.createXMLStreamReader(
                new LimitedInputStream(zip.getInputStream(entry), entry.getSize(), progress), "UTF-8");
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining = MAX_ENTRY_BYTES;
        private final long size;
        private final DoubleConsumer progress;
        private long read;
        private long nextReport;

        LimitedInputStream(InputStream in, long size, DoubleConsumer progress) {
            super(in);
            this.size = size;
            this.progress = size > 0 ? progress : null;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                consume(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                consume(read);
            }
            return read;
        }

        private void consume(long bytes) throws IOException {
            remaining -= bytes;
            if (remaining < 0) {
                throw new IOException("Część skoroszytu przekracza dopuszczalny rozmiar");
            }
            read += bytes;
            if (progress != null && read >= nextReport) {
                progress.accept(Math.min(1, (double) read / size));
                nextReport = read + size / 100;
            }
        }
    }
}
