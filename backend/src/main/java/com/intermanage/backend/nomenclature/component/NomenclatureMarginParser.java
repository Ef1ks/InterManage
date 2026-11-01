package com.intermanage.backend.nomenclature.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.nomenclature.dto.ParsedMarginFile;
import com.intermanage.backend.product.component.ProductReportParser;

/**
 * Czyta plik marż wymaganych przygotowany z szablonu ({@code NomenclatureMarginService#template}): wiersz nagłówka
 * z kolumnami "Kod" i "Marża %", potem po jednej pozycji nomenklatury w wierszu. Marża w zwykłych liczbach
 * (20 = 20%), pusta komórka = bez progu. Jeden błędny wiersz odrzuca cały plik.
 */
@Component
public class NomenclatureMarginParser {

    public static final String CODE_HEADER = "Kod";
    public static final String MARGIN_HEADER = "Marża %";

    private static final BigDecimal MAX_MARGIN = new BigDecimal("99.99");

    private final XlsxReader xlsxReader;

    public NomenclatureMarginParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public ParsedMarginFile parse(Path file) throws IOException, InvalidReportException {
        FileReader reader = new FileReader();
        try {
            xlsxReader.readFirstSheet(file, reader::onRow);
        } catch (InvalidWorkbookException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku. Zapisz go w Excelu jako .xlsx.");
        }
        return reader.finish();
    }

    /** Stan czytania jednego pliku; bean parsera jest współdzielony. */
    private static final class FileReader {

        private static final String CODE = ReportText.normalize(CODE_HEADER);
        private static final String MARGIN = ReportText.normalize(MARGIN_HEADER);

        private Integer codeColumn;
        private Integer marginColumn;
        private final Map<String, BigDecimal> margins = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onRow(int rowNumber, List<String> cells) {
            if (codeColumn == null) {
                readHeader(cells);
                return;
            }
            String rawCode = cell(cells, codeColumn);
            String rawMargin = cell(cells, marginColumn);
            if (rawCode == null && rawMargin == null) {
                return;
            }
            try {
                String code = code(rawCode);
                if (margins.containsKey(code)) {
                    throw new IllegalArgumentException("kod " + code + " jest w pliku drugi raz");
                }
                margins.put(code, margin(rawMargin));
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + rowNumber + ": " + e.getMessage());
                }
            }
        }

        private void readHeader(List<String> cells) {
            Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                String value = cells.get(i);
                if (value != null && !value.isBlank()) {
                    header.putIfAbsent(ReportText.normalize(value), i);
                }
            }
            if (header.containsKey(CODE) && header.containsKey(MARGIN)) {
                codeColumn = header.get(CODE);
                marginColumn = header.get(MARGIN);
            }
        }

        private ParsedMarginFile finish() throws InvalidReportException {
            if (codeColumn == null) {
                throw new InvalidReportException("W pliku nie ma wiersza z kolumnami „" + CODE_HEADER + "” i „"
                        + MARGIN_HEADER + "”. Pobierz plik do uzupełnienia i wpisz marże w nim.");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zapisane.", errors,
                        errorCount);
            }
            if (margins.isEmpty()) {
                throw new InvalidReportException("Plik nie zawiera żadnej nomenklatury");
            }
            return new ParsedMarginFile(margins);
        }

        private static String cell(List<String> cells, int column) {
            String value = column < cells.size() ? cells.get(column) : null;
            return value == null || value.isBlank() ? null : value.trim();
        }

        /** "010101"; liczba z Excela gubi zera wiodące ("10101", "1.0"), więc dopełniamy do parzystej długości. */
        private static String code(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("brak kodu nomenklatury");
            }
            String code = raw.replace(" ", "").replaceFirst("\\.0+$", "");
            if (!code.matches("\\d{1,6}")) {
                throw new IllegalArgumentException("„" + raw + "” to nie kod nomenklatury");
            }
            return code.length() % 2 == 1 ? "0" + code : code;
        }

        /** Zwykła liczba: 20 = 20%. Komórka w formacie procentowym (0,2) jest odrzucana, żeby 20% nie stało się 0,2%. */
        private static BigDecimal margin(String raw) {
            if (raw == null) {
                return null;
            }
            BigDecimal value;
            try {
                value = new BigDecimal(raw.replace(" ", "").replace("%", "").replace(',', '.'));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("„" + raw + "” to nie liczba");
            }
            if (value.signum() > 0 && value.compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("marża " + raw + " wygląda na format procentowy Excela. "
                        + "Wpisz zwykłą liczbę: 20 oznacza 20%");
            }
            value = value.setScale(2, RoundingMode.HALF_UP);
            if (value.signum() < 0 || value.compareTo(MAX_MARGIN) > 0) {
                throw new IllegalArgumentException("marża musi być między 0 a 99,99");
            }
            return value;
        }
    }
}
