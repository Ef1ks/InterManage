package com.intermanage.backend.common.component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Najprostszy skoroszyt w starym formacie Excela (.xls, BIFF8 w kontenerze OLE) z jednym arkuszem, dla programów,
 * które nie czytają .xlsx. Bez Apache POI: pierwszy wiersz to nagłówek, teksty zapisujemy jako tekst (kody zachowują
 * zera wiodące), liczby ({@link Number}) jako liczby, {@code null} to pusta komórka.
 * <p>
 * Plik jak zapisany przez Excela: rekordy skoroszytu i ustawień arkusza ze wzoru ({@code resources/xls}, wycięte
 * z pliku CADBEZP zapisanego w Excelu 2010), teksty we wspólnej tablicy SST i komórkach LABELSST, liczby jako RK
 * (kilka kolejnych jako MULRK) albo NUMBER według reguły Excela, wiersze ROW w blokach po 32 z DBCELL i INDEX, CLSID
 * Excela przy katalogu głównym i strumienie SummaryInformation / DocumentSummaryInformation. Program sklepu nie
 * wczytywał pliku składanego inaczej („Błąd podczas czytania pliku CadArtFrs.xls”), dopóki nie zapisało się go
 * w Excelu.
 */
@Component
public class XlsWriter {

    /**
     * Komórka z pustym tekstem (rekord tekstu „”), jak kolumna Segment w pliku CADBEZP z Impeka; zwykły {@code ""}
     * i {@code null} to komórka bez rekordu.
     */
    public static final Object EMPTY_TEXT = new Object() {
        @Override
        public String toString() {
            return "";
        }
    };

    private static final int SECTOR = 512;
    /** Strumień krótszy niż 4096 bajtów musiałby iść do mini strumienia; dopełniamy go zerami (jak xlwt). */
    private static final int MIN_STREAM = 4096;
    private static final int END_OF_CHAIN = 0xFFFFFFFE;
    private static final int FAT_SECTOR = 0xFFFFFFFD;
    private static final int FREE = 0xFFFFFFFF;
    /** XF komórek: pierwsze 15 to style, 15. to domyślny format komórki. */
    private static final int CELL_XF = 15;
    /** Największa treść rekordu BIFF8; dłuższa tablica SST idzie w rekordach CONTINUE. */
    private static final int MAX_RECORD = 8224;
    private static final int ROWS_PER_BLOCK = 32;
    private static final int ROW_RECORD = 20;
    /** Wysokość wiersza w 1/20 punktu, jak w pliku CADBEZP z Impeka (i DEFAULTROWHEIGHT wzoru). */
    private static final int ROW_HEIGHT = 300;

    public byte[] write(String sheetName, List<String> headers, List<List<Object>> rows) {
        if (rows.size() + 1 > 65_536 || headers.size() > 256) {
            throw new IllegalArgumentException("Arkusz .xls ma najwyżej 65536 wierszy i 256 kolumn");
        }
        List<List<Object>> all = new ArrayList<>(rows.size() + 1);
        all.add(new ArrayList<Object>(headers));
        all.addAll(rows);
        int columns = Math.max(headers.size(), rows.stream().mapToInt(List::size).max().orElse(0));

        SharedStrings strings = new SharedStrings();
        List<byte[]> blocks = new ArrayList<>();
        for (int first = 0; first < all.size(); first += ROWS_PER_BLOCK) {
            blocks.add(rowBlock(all, first, Math.min(first + ROWS_PER_BLOCK, all.size()), strings));
        }

        // Skoroszyt jak plik CADBEZP z Impeka: nasz arkusz z danymi i dwa puste („Arkusz2”, „Arkusz3”).
        List<byte[]> names = List.of(shortString(sheetName), shortString("Arkusz2"), shortString("Arkusz3"));
        int boundSheets = names.stream().mapToInt(name -> 4 + 6 + name.length).sum();
        int sstAt = GLOBALS_HEAD.length + boundSheets + GLOBALS_MID.length;
        Records sst = strings.records(sstAt);
        int sheetStart = sstAt + sst.size() + GLOBALS_TAIL.length + 4; // + EOF

        // Arkusz: BOF, INDEX (pozycje DBCELL w strumieniu), ustawienia arkusza ze wzoru, DEFCOLWIDTH, DIMENSIONS,
        // bloki wierszy, zakończenie ze wzoru (WINDOW2, PLV, SELECTION, FEATHDR), EOF.
        int indexSize = 4 + 16 + 4 * blocks.size();
        int defColWidthAt = sheetStart + SHEET_BOF.length + indexSize + SHEET_SETTINGS.length;
        int blocksAt = defColWidthAt + 6 + 18;
        ByteBuffer index = buffer(16 + 4 * blocks.size()).putInt(0).putInt(0).putInt(all.size()).putInt(defColWidthAt);
        int position = blocksAt;
        for (int i = 0; i < blocks.size(); i++) {
            position += blocks.get(i).length;
            int rowsInBlock = Math.min(ROWS_PER_BLOCK, all.size() - i * ROWS_PER_BLOCK);
            index.putInt(position - (8 + 2 * rowsInBlock)); // DBCELL zamyka blok
        }
        Records sheet = new Records();
        sheet.raw(SHEET_BOF);
        sheet.add(0x020B, index.array());
        sheet.raw(SHEET_SETTINGS);
        sheet.add(0x0055, buffer(2).putShort((short) 8).array());
        sheet.add(0x0200, buffer(14).putInt(0).putInt(all.size()).putShort((short) 0)
                .putShort((short) columns).putShort((short) 0).array());
        for (byte[] block : blocks) {
            sheet.raw(block);
        }
        sheet.raw(SHEET_END);
        sheet.add(0x000A, new byte[0]);

        int[] offsets = {sheetStart, sheetStart + sheet.size(), sheetStart + sheet.size() + EMPTY_SHEET.length};
        Records globals = new Records();
        globals.raw(GLOBALS_HEAD);
        for (int i = 0; i < names.size(); i++) {
            byte[] name = names.get(i);
            globals.add(0x0085, ByteBuffer.allocate(6 + name.length).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(offsets[i]).put((byte) 0).put((byte) 0).put(name).array());
        }
        globals.raw(GLOBALS_MID);
        globals.raw(sst.bytes());
        globals.raw(GLOBALS_TAIL);
        globals.add(0x000A, new byte[0]);

        ByteArrayOutputStream workbook = new ByteArrayOutputStream();
        workbook.writeBytes(globals.bytes());
        workbook.writeBytes(sheet.bytes());
        workbook.writeBytes(emptySheet(offsets[1]));
        workbook.writeBytes(emptySheet(offsets[2]));
        return compoundFile(workbook.toByteArray());
    }

    /** Pusty arkusz ze wzoru; INDEX wskazuje DEFCOLWIDTH bezwzględną pozycją w strumieniu, więc ją przesuwamy. */
    private static byte[] emptySheet(int start) {
        byte[] sheet = EMPTY_SHEET.clone();
        ByteBuffer.wrap(sheet).order(ByteOrder.LITTLE_ENDIAN).putInt(EMPTY_SHEET_INDEX_DEFCOLWIDTH,
                start + EMPTY_SHEET_DEFCOLWIDTH);
        return sheet;
    }

    /*
     * Wzór: plik CADBEZP z Impeka (zapisany przez Excela 2010), który program sklepu wczytuje ({@code resources/xls}):
     * skoroszyt od BOF po USESELFS, MTRSETTINGS…RECALCID i BOOKEXT…COMPAT12 (między nimi nasze BOUNDSHEET i SST),
     * BOF i ustawienia arkusza, jego zakończenie i pusty arkusz. Program przyjmował tylko taki plik, więc poza danymi
     * niczego nie składamy sami.
     */
    private static final byte[] GLOBALS_HEAD = template("globals-head.bin");
    private static final byte[] GLOBALS_MID = template("globals-mid.bin");
    private static final byte[] GLOBALS_TAIL = template("globals-tail.bin");
    private static final byte[] SHEET_BOF = template("sheet-bof.bin");
    /** CALCMODE…HEADERFOOTER: przeliczanie, druk i marginesy. */
    private static final byte[] SHEET_SETTINGS = template("sheet-settings.bin");
    /** WINDOW2, PLV, SELECTION, FEATHDR. */
    private static final byte[] SHEET_END = template("sheet-end.bin");
    /** Pusty arkusz (BOF…EOF) jak „Arkusz2” i „Arkusz3” w pliku Impeka. */
    private static final byte[] EMPTY_SHEET = template("empty-sheet.bin");
    /** Pole INDEX z pozycją DEFCOLWIDTH (BOF 20 bajtów + nagłówek INDEX 4 + 12) i pozycja DEFCOLWIDTH w arkuszu. */
    private static final int EMPTY_SHEET_INDEX_DEFCOLWIDTH = 36;
    private static final int EMPTY_SHEET_DEFCOLWIDTH = 274;

    private static byte[] template(String name) {
        try (var in = XlsWriter.class.getResourceAsStream("/xls/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Brak wzoru /xls/" + name);
            }
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] hex(String hex) {
        return java.util.HexFormat.of().parseHex(hex);
    }

    /** Wiersze [first, last): najpierw ich rekordy ROW, potem komórki wiersz po wierszu, na końcu DBCELL. */
    private static byte[] rowBlock(List<List<Object>> all, int first, int last, SharedStrings strings) {
        Records rowRecords = new Records();
        List<byte[]> cells = new ArrayList<>();
        for (int row = first; row < last; row++) {
            List<Object> values = all.get(row);
            // Jak w pliku z Impeka: tekst z ostatniej kolumny wiersza trafia do tablicy SST przed resztą wiersza.
            for (int col = values.size() - 1; col >= 0; col--) {
                Object value = values.get(col);
                if (value != null && !(value instanceof Number)) {
                    if (!value.toString().isEmpty() || value == EMPTY_TEXT) {
                        strings.reserve(text(value));
                    }
                    break;
                }
            }
            Records rowCells = new Records();
            int firstCol = -1;
            int lastCol = -1;
            // Kolejne liczby RK idą jednym rekordem MULRK (jak w Excelu), pojedyncza jako RK.
            List<Integer> rkRun = new ArrayList<>();
            int rkStart = -1;
            for (int col = 0; col < values.size(); col++) {
                Integer rk = rkValue(values.get(col));
                if (rk != null && rkStart >= 0 && rkStart + rkRun.size() == col) {
                    rkRun.add(rk);
                } else {
                    flushRk(rowCells, row, rkStart, rkRun);
                    rkRun.clear();
                    rkStart = -1;
                    if (rk != null) {
                        rkStart = col;
                        rkRun.add(rk);
                    } else if (!cell(rowCells, row, col, values.get(col), strings)) {
                        continue;
                    }
                }
                firstCol = firstCol < 0 ? col : firstCol;
                lastCol = col;
            }
            flushRk(rowCells, row, rkStart, rkRun);
            rowRecords.add(0x0208, buffer(16).putShort((short) row).putShort((short) Math.max(firstCol, 0))
                    .putShort((short) (lastCol + 1)).putShort((short) ROW_HEIGHT).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0x0100).putShort((short) CELL_XF).array());
            cells.add(rowCells.bytes());
        }
        // DBCELL: odległość od pierwszego ROW do DBCELL, potem do pierwszej komórki każdego wiersza (pierwsza
        // liczona od drugiego rekordu ROW, kolejne od pierwszej komórki poprzedniego wiersza).
        int rowsSize = rowRecords.size();
        int cellsSize = cells.stream().mapToInt(c -> c.length).sum();
        ByteBuffer dbCell = buffer(4 + 2 * cells.size()).putInt(rowsSize + cellsSize);
        int offset = rowsSize - ROW_RECORD;
        for (byte[] rowCells : cells) {
            dbCell.putShort((short) offset);
            offset = rowCells.length;
        }
        Records block = new Records();
        block.raw(rowRecords.bytes());
        cells.forEach(block::raw);
        block.add(0x00D7, dbCell.array());
        return block.bytes();
    }

    /** Liczba, którą da się zapisać jako RK; inaczej {@code null} (tekst, pusta albo liczba do rekordu NUMBER). */
    private static Integer rkValue(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        return rk(number instanceof BigDecimal decimal ? decimal.doubleValue() : number.doubleValue());
    }

    /** Ciąg liczb RK od kolumny {@code first}: jedna jako RK, kilka jako MULRK. */
    private static void flushRk(Records sheet, int row, int first, List<Integer> values) {
        if (values.isEmpty()) {
            return;
        }
        if (values.size() == 1) {
            sheet.add(0x027E, buffer(10).putShort((short) row).putShort((short) first).putShort((short) CELL_XF)
                    .putInt(values.getFirst()).array());
            return;
        }
        ByteBuffer mulRk = buffer(6 + 6 * values.size()).putShort((short) row).putShort((short) first);
        values.forEach(rk -> mulRk.putShort((short) CELL_XF).putInt(rk));
        sheet.add(0x00BD, mulRk.putShort((short) (first + values.size() - 1)).array());
    }

    /** Zapisuje komórkę i mówi, czy coś zapisał (pusta komórka nie ma rekordu). */
    private static boolean cell(Records sheet, int row, int col, Object value, SharedStrings strings) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number number) {
            double numeric = number instanceof BigDecimal decimal ? decimal.doubleValue() : number.doubleValue();
            Integer rk = rk(numeric);
            if (rk != null) {
                sheet.add(0x027E, buffer(10).putShort((short) row).putShort((short) col).putShort((short) CELL_XF)
                        .putInt(rk).array());
            } else {
                sheet.add(0x0203, buffer(14).putShort((short) row).putShort((short) col).putShort((short) CELL_XF)
                        .putDouble(numeric).array());
            }
            return true;
        }
        String text = value.toString();
        if (text.isEmpty() && value != EMPTY_TEXT) {
            return false;
        }
        sheet.add(0x00FD, buffer(10).putShort((short) row).putShort((short) col).putShort((short) CELL_XF)
                .putInt(strings.index(text(value))).array());
        return true;
    }

    /** Tekst komórki, najwyżej 255 znaków. */
    private static String text(Object value) {
        String text = value.toString();
        return text.length() > 255 ? text.substring(0, 255) : text;
    }

    /**
     * Liczba jako RK tak, jak w plikach CADBEZP z Impeka (sprawdzone na wszystkich 114 liczbach dwóch plików): zawsze
     * górne 32 bity double'a (1 → {@code 0x3FF00000}, 0 → {@code 0}), a gdy dolne bity nie są zerowe, double setek
     * z flagą „÷100”, ale tylko gdy mnożenie przez 100 daje dokładnie liczbę całkowitą (0,15 → 15,0, 5,90 → 590,0).
     * Bez całkowitego RK. Inaczej {@code null} i rekord NUMBER (9,95; 16,99).
     */
    static Integer rk(double value) {
        Integer plain = floatRk(value);
        if (plain != null) {
            return plain;
        }
        // Jak Excel: tylko gdy mnożenie przez 100 daje dokładnie liczbę całkowitą (9,95 × 100 = 994,999… idzie do
        // NUMBER, nie do zaokrąglonego RK).
        double hundredths = value * 100;
        if (hundredths == Math.rint(hundredths)) {
            Integer scaled = floatRk(hundredths);
            if (scaled != null) {
                return scaled | 0x1;
            }
        }
        return null;
    }

    /** Górne 32 bity double'a, gdy dolne 32 są zerowe (RK bez flag). */
    private static Integer floatRk(double value) {
        long bits = Double.doubleToLongBits(value);
        return (bits & 0x3_FFFF_FFFFL) == 0 ? (int) (bits >>> 32) : null;
    }

    /** Napis z 1-bajtową długością (nazwa arkusza): jednobajtowy jak w Excelu, UTF-16 tylko z polskimi znakami. */
    private static byte[] shortString(String text) {
        boolean compressed = text.chars().allMatch(c -> c <= 0xFF);
        byte[] chars = text.getBytes(compressed ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(2 + chars.length).put((byte) text.length()).put((byte) (compressed ? 0 : 1))
                .put(chars).array();
    }

    /**
     * Napis z 2-bajtową długością jak w SST Excela: jednobajtowy (ISO-8859-1), gdy wszystkie znaki się w nim mieszczą,
     * inaczej UTF-16 (polskie znaki).
     */
    private static byte[] unicodeString(String text) {
        boolean compressed = text.chars().allMatch(c -> c <= 0xFF);
        byte[] chars = text.getBytes(compressed ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(3 + chars.length).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) text.length()).put((byte) (compressed ? 0 : 1)).put(chars).array();
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Wspólna tablica tekstów (SST): każdy tekst raz, komórki wskazują go numerem. */
    private static final class SharedStrings {

        private final Map<String, Integer> indexes = new LinkedHashMap<>();
        private int references;

        int index(String text) {
            references++;
            return indexes.computeIfAbsent(text, t -> indexes.size());
        }

        /** Dopisuje tekst do tablicy bez odwołania z komórki (ustala tylko kolejność). */
        void reserve(String text) {
            indexes.computeIfAbsent(text, t -> indexes.size());
        }

        /**
         * Rekord SST (z CONTINUE, gdy się nie mieści; napisy dzielimy tylko na ich granicach) i EXTSST z pozycjami
         * co {@code bucket}-tego napisu; {@code at} to pozycja rekordu SST w strumieniu.
         */
        Records records(int at) {
            List<byte[]> encoded = indexes.keySet().stream().map(XlsWriter::unicodeString).toList();
            int bucket = Math.max(8, (encoded.size() + 127) / 128);
            ByteBuffer extSst = buffer(2 + 8 * ((encoded.size() + bucket - 1) / bucket)).putShort((short) bucket);

            Records records = new Records();
            ByteArrayOutputStream current = new ByteArrayOutputStream();
            current.writeBytes(buffer(8).putInt(references).putInt(encoded.size()).array());
            int type = 0x00FC;
            for (int i = 0; i < encoded.size(); i++) {
                byte[] string = encoded.get(i);
                if (current.size() + string.length > MAX_RECORD) {
                    records.add(type, current.toByteArray());
                    current.reset();
                    type = 0x003C;
                }
                if (i % bucket == 0) {
                    int inRecord = 4 + current.size();
                    extSst.putInt(at + records.size() + inRecord).putShort((short) inRecord).putShort((short) 0);
                }
                current.writeBytes(string);
            }
            records.add(type, current.toByteArray());
            records.add(0x00FF, extSst.array());
            return records;
        }
    }

    /** Rekordy BIFF: typ, długość, dane. */
    private static final class Records {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void add(int type, byte[] data) {
            out.writeBytes(buffer(4).putShort((short) type).putShort((short) data.length).array());
            out.writeBytes(data);
        }

        void raw(byte[] records) {
            out.writeBytes(records);
        }

        int size() {
            return out.size();
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    /** CLSID skoroszytu Excela 97-2003 (00020820-0000-0000-C000-000000000046) przy katalogu głównym, jak w Excelu. */
    private static final String EXCEL_CLSID = "2008020000000000c000000000000046";
    /** Właściwości dokumentu ze wzoru (autor „***”, strona kodowa 1250, arkusz „Arkusz1”), jak zapisał je Excel. */
    private static final byte[] SUMMARY_INFORMATION = template("summary-information.bin");
    private static final byte[] DOCUMENT_SUMMARY_INFORMATION = template("document-summary-information.bin");

    /**
     * Kontener OLE (Compound File v3, sektory po 512 bajtów) jak z Excela: strumienie „Workbook”,
     * „\u0005SummaryInformation” i „\u0005DocumentSummaryInformation” (każdy co najmniej 4096 bajtów, więc bez mini
     * strumienia), sektor katalogu i tablica FAT.
     */
    private static byte[] compoundFile(byte[] workbook) {
        List<byte[]> streams = List.of(workbook, SUMMARY_INFORMATION, DOCUMENT_SUMMARY_INFORMATION);
        int[] sizes = streams.stream().mapToInt(stream -> Math.max(stream.length, MIN_STREAM)).toArray();
        int[] starts = new int[streams.size()];
        int sectors = 0;
        for (int i = 0; i < streams.size(); i++) {
            starts[i] = sectors;
            sectors += (sizes[i] + SECTOR - 1) / SECTOR;
        }
        int streamSectors = sectors;
        int fatSectors = 1;
        while ((streamSectors + 1 + fatSectors + 127) / 128 > fatSectors) {
            fatSectors++;
        }
        if (fatSectors > 109) {
            throw new IllegalArgumentException("Plik .xls jest za duży");
        }
        // Jak w pliku z Impeka: po strumieniach sektory FAT, za nimi katalog.
        int firstFat = streamSectors;
        int directorySector = streamSectors + fatSectors;

        ByteBuffer file = buffer(SECTOR * (1 + streamSectors + 1 + fatSectors));
        file.putLong(0xE11AB1A1E011CFD0L).put(new byte[16]).putShort((short) 0x003E).putShort((short) 0x0003)
                .putShort((short) 0xFFFE).putShort((short) 9).putShort((short) 6).put(new byte[6])
                .putInt(0).putInt(fatSectors).putInt(directorySector).putInt(0).putInt(MIN_STREAM)
                .putInt(END_OF_CHAIN).putInt(0).putInt(END_OF_CHAIN).putInt(0);
        for (int i = 0; i < 109; i++) {
            file.putInt(i < fatSectors ? firstFat + i : FREE);
        }

        for (int i = 0; i < streams.size(); i++) {
            file.position(SECTOR * (1 + starts[i]));
            file.put(streams.get(i));
        }

        // Drzewo katalogu jak w Excelu (czerwono-czarne, wszystkie czarne): korzeń wskazuje SummaryInformation,
        // a ono krótszą nazwę „Workbook” po lewej i DocumentSummaryInformation po prawej.
        file.position(SECTOR * (1 + directorySector));
        file.put(directoryEntry("Root Entry", 5, FREE, FREE, 2, hex(EXCEL_CLSID), END_OF_CHAIN, 0));
        file.put(directoryEntry("Workbook", 2, FREE, FREE, FREE, new byte[16], starts[0], sizes[0]));
        file.put(directoryEntry("\u0005SummaryInformation", 2, 1, 3, FREE, new byte[16], starts[1], sizes[1]));
        file.put(directoryEntry("\u0005DocumentSummaryInformation", 2, FREE, FREE, FREE, new byte[16], starts[2],
                sizes[2]));

        file.position(SECTOR * (1 + firstFat));
        for (int sector = 0; sector < fatSectors * 128; sector++) {
            int next;
            if (sector < streamSectors) {
                boolean last = false;
                for (int i = 0; i < streams.size(); i++) {
                    last |= sector == starts[i] + (sizes[i] + SECTOR - 1) / SECTOR - 1;
                }
                next = last ? END_OF_CHAIN : sector + 1;
            } else if (sector < firstFat + fatSectors) {
                next = FAT_SECTOR;
            } else if (sector == directorySector) {
                next = END_OF_CHAIN;
            } else {
                next = FREE;
            }
            file.putInt(next);
        }
        return file.array();
    }

    private static byte[] directoryEntry(String name, int type, int left, int right, int child, byte[] clsid,
            int start, int size) {
        ByteBuffer entry = buffer(128);
        byte[] chars = name.getBytes(StandardCharsets.UTF_16LE);
        entry.put(chars).position(64);
        entry.putShort((short) (name.isEmpty() ? 0 : chars.length + 2)).put((byte) type).put((byte) 1)
                .putInt(left).putInt(right).putInt(child).put(clsid).putInt(0).putLong(0).putLong(0)
                .putInt(start).putInt(size).putInt(0);
        return entry.array();
    }
}
