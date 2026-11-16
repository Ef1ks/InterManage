package com.intermanage.backend.mea.component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Plik zamówienia MEA do ATHOS jak w eMEA ({@code GenerujZip.buttonGeneruj_Click}): tekst bez separatorów i końców
 * linii, rekord 45 znaków na każdą pozycję MEA (także bez kartonów): numer MEA (5), ITM8 (8, bez zer wiodących,
 * zaczyna się od 6), cztery rozrzuty po 4 cyfry kartonów z zerami z przodu i 16 zer (rozrzuty 5-8). Nazwa
 * {@code V<nr MEA><2 pierwsze cyfry sklepu>.<3 ostatnie>}, a ATHOS dostaje go spakowanego w ZIP-ie o tej samej nazwie
 * z {@code R} zamiast {@code V}. Sprawdzenie jak {@code WeryfikujTresc} w eMEA.
 */
public final class AthosOrderFile {

    public static final int RECORD_LENGTH = 45;
    public static final int DISPATCHES = 4;
    private static final String EMPTY_DISPATCHES = "0".repeat(16);

    /** Błąd danych zamówienia; komunikat idzie do użytkownika. */
    public static class InvalidOrder extends RuntimeException {
        public InvalidOrder(String message) {
            super(message);
        }
    }

    /** Pozycja: ITM8 promocyjny z formularza i kartony na rozrzuty R1-R4. */
    public record Line(String itm8, int[] colis) {
    }

    private AthosOrderFile() {
    }

    /** Numer sklepu jak w eMEA: 5 cyfr, pierwsza 0 albo 1, od 2000 do 19999. */
    public static String storeNumber(String number) {
        String value = number == null ? "" : number.strip();
        if (!value.matches("[01]\\d{4}") || Integer.parseInt(value) < 2000 || Integer.parseInt(value) > 19999) {
            throw new InvalidOrder("Numer sklepu " + value + " nie pasuje do pliku ATHOS (5 cyfr, od 02000 do 19999)");
        }
        return value;
    }

    public static String textName(String meaNumber, String store) {
        return "V" + meaNumber + store.substring(0, 2) + "." + store.substring(2, 5);
    }

    public static String zipName(String meaNumber, String store) {
        return "R" + meaNumber + store.substring(0, 2) + "." + store.substring(2, 5);
    }

    /** Treść pliku V…; każda pozycja formularza jest w nim raz, w kolejności formularza. */
    public static String content(String meaNumber, List<Line> lines) {
        if (!meaNumber.matches("\\d{5}")) {
            throw new InvalidOrder("Numer MEA " + meaNumber + " musi mieć 5 cyfr");
        }
        if (lines.isEmpty()) {
            throw new InvalidOrder("MEA " + meaNumber + " nie ma pozycji");
        }
        StringBuilder text = new StringBuilder(lines.size() * RECORD_LENGTH);
        int position = 1;
        for (Line line : lines) {
            String itm8 = itm8(line.itm8());
            if (itm8 == null) {
                throw new InvalidOrder("Nieprawidłowy kod ITM w pozycji nr " + position + " (" + line.itm8() + ")");
            }
            text.append(meaNumber).append(itm8);
            for (int dispatch = 0; dispatch < DISPATCHES; dispatch++) {
                int colis = dispatch < line.colis().length ? line.colis()[dispatch] : 0;
                if (colis < 0 || colis > 9999) {
                    throw new InvalidOrder("Nieprawidłowa ilość COLI w pozycji nr " + position);
                }
                text.append(String.format("%04d", colis));
            }
            text.append(EMPTY_DISPATCHES);
            position++;
        }
        if (text.length() % RECORD_LENGTH != 0) {
            throw new InvalidOrder("Nieprawidłowa długość pliku z zamówieniem");
        }
        return text.toString();
    }

    /** Pozycja odczytana z treści pliku V…: ITM8 bez zer wiodących i kartony R1-R4. */
    public record Record(String itm8, int[] colis) {
    }

    /** Odczyt treści pliku V… zapisanej w bazie; rekordy ucięte albo z niecyframi są pomijane. */
    public static List<Record> parse(String content) {
        List<Record> records = new java.util.ArrayList<>();
        if (content == null) {
            return records;
        }
        for (int start = 0; start + RECORD_LENGTH <= content.length(); start += RECORD_LENGTH) {
            String record = content.substring(start, start + RECORD_LENGTH);
            if (!record.matches("\\d{" + RECORD_LENGTH + "}")) {
                continue;
            }
            int[] colis = new int[DISPATCHES];
            for (int dispatch = 0; dispatch < DISPATCHES; dispatch++) {
                int from = 13 + dispatch * 4;
                colis[dispatch] = Integer.parseInt(record.substring(from, from + 4));
            }
            records.add(new Record(Long.toString(Long.parseLong(record.substring(5, 13))), colis));
        }
        return records;
    }

    /** ZIP z jednym plikiem (nazwa bez folderu, jak {@code ZipFile.AddFile(plik, "")} w eMEA). */
    public static byte[] zip(String textName, String content) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(textName));
            zip.write(content.getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    /** ITM8 jak {@code int.Parse(kod).ToString()} w eMEA: bez zer wiodących, dokładnie 8 cyfr, pierwsza 6. */
    private static String itm8(String value) {
        if (value == null || !value.strip().matches("\\d{1,13}")) {
            return null;
        }
        String plain = Long.toString(Long.parseLong(value.strip()));
        return plain.length() == 8 && plain.startsWith("6") ? plain : null;
    }
}
