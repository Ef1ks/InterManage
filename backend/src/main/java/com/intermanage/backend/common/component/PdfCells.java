package com.intermanage.backend.common.component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.FinalText;
import com.lowagie.text.pdf.parser.ParsedText;
import com.lowagie.text.pdf.parser.ParsedTextImpl;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.lowagie.text.pdf.parser.TextAssembler;
import com.lowagie.text.pdf.parser.Word;

/**
 * Napisy z raportów PDF systemu sklepu razem z pozycją na stronie. Każda komórka tabeli to w tych PDF-ach osobny
 * napis, ale zwykły odczyt tekstu skleja sąsiednie komórki ("PIECZYWO CHRUPKIE1kg143"), więc parsery raportów składają
 * wiersze i kolumny same z pozycji napisów.
 */
public final class PdfCells {

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F'};

    private PdfCells() {
    }

    /** Napis z PDF-u (jedna komórka) i jego pozycja na stronie: dwie współrzędne, bez założeń o obrocie strony. */
    public record Cell(float a, float b, String text) {
    }

    public interface PageHandler {
        void page(int page, int pageCount, List<Cell> cells);
    }

    /** Czy plik zaczyna się sygnaturą PDF (rozpoznanie po bajtach, nie po nazwie). */
    public static boolean isPdf(Path file) throws IOException {
        byte[] start = new byte[PDF_SIGNATURE.length];
        try (var input = Files.newInputStream(file)) {
            return input.readNBytes(start, 0, start.length) == start.length && Arrays.equals(start, PDF_SIGNATURE);
        }
    }

    /**
     * Podaje napisy kolejnych stron. Plik, którego nie da się otworzyć jako PDF, daje {@link IOException}; błąd przy
     * odczycie tekstu wychodzi jako {@link RuntimeException} (parser zamienia go na komunikat o swoim raporcie).
     */
    public static void read(Path file, PageHandler handler) throws IOException {
        PdfReader reader;
        try {
            // Z bajtów, nie ze ścieżki: OpenPDF mapuje plik w pamięci, a zmapowanego pliku tymczasowego Windows nie
            // pozwala usunąć. Raporty mają najwyżej kilka MB.
            reader = new PdfReader(Files.readAllBytes(file));
        } catch (RuntimeException e) {
            throw new IOException("Nie udało się otworzyć pliku PDF", e);
        }
        try {
            CellCollector collector = new CellCollector();
            PdfTextExtractor extractor = new PdfTextExtractor(reader, collector);
            int pageCount = reader.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                collector.cells.clear();
                extractor.getTextFromPage(page);
                handler.page(page, pageCount, List.copyOf(collector.cells));
            }
        } finally {
            reader.close();
        }
    }

    /**
     * Zbiera napisy strony z pozycjami. Napis dekodujemy przez słowa ({@link ParsedText#getAsPartialWords}), bo tak
     * robi to sam OpenPDF; {@link ParsedText#getText} nie radzi sobie z fontami Identity-H z tych raportów.
     */
    private static final class CellCollector implements TextAssembler {

        private final List<Cell> cells = new ArrayList<>();

        @Override
        public void process(ParsedText text, String contextName) {
            List<Word> words = text.getAsPartialWords();
            if (words.isEmpty()) {
                return;
            }
            String joined = clean(words.stream().map(Word::getText).collect(Collectors.joining()));
            if (!joined.isEmpty()) {
                var startPoint = words.getFirst().getStartPoint();
                cells.add(new Cell(startPoint.get(0), startPoint.get(1), joined));
            }
        }

        @Override
        public void process(Word word, String contextName) {
        }

        @Override
        public void process(FinalText text, String contextName) {
        }

        @Override
        public void renderText(FinalText text) {
        }

        @Override
        public void renderText(ParsedTextImpl text) {
        }

        @Override
        public FinalText endParsingContext(String contextName) {
            return new FinalText("");
        }

        @Override
        public String getWordId() {
            return "word";
        }

        @Override
        public void setPage(int page) {
        }

        @Override
        public void reset() {
        }

        /** Data w nagłówku ma przed sobą znacznik BOM zapisany jako zwykłe znaki ("ï»¿"). */
        private static String clean(String value) {
            return value.replaceAll("[\\uFEFF\\u00EF\\u00BB\\u00BF]", "").replaceAll("\\s+", " ").trim();
        }
    }
}
