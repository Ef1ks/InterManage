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
 * Układ rekordów jak w pliku zapisanym przez Excela (teksty we wspólnej tablicy SST i komórkach LABELSST, liczby jako
 * RK, wiersze ROW w blokach po 32 z DBCELL i INDEX), bo program sklepu nie wczytywał pliku z komórkami LABEL i NUMBER,
 * dopóki nie otworzyło się go i nie zapisało w Excelu.
 */
@Component
public class XlsWriter {

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
    /** Wysokość wiersza w 1/20 punktu, jak we wzorze z Excela. */
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

        Records globals = globals(sheetName, strings);
        int sheetStart = globals.size();

        // Arkusz: BOF, INDEX (pozycje DBCELL w strumieniu), DEFCOLWIDTH, DIMENSIONS, bloki wierszy, WINDOW2, EOF.
        int indexSize = 4 + 16 + 4 * blocks.size();
        int defColWidthAt = sheetStart + 20 + indexSize;
        int blocksAt = defColWidthAt + 6 + 18;
        ByteBuffer index = buffer(16 + 4 * blocks.size()).putInt(0).putInt(0).putInt(all.size()).putInt(defColWidthAt);
        int position = blocksAt;
        for (int i = 0; i < blocks.size(); i++) {
            position += blocks.get(i).length;
            int rowsInBlock = Math.min(ROWS_PER_BLOCK, all.size() - i * ROWS_PER_BLOCK);
            index.putInt(position - (8 + 2 * rowsInBlock)); // DBCELL zamyka blok
        }
        Records sheet = new Records();
        sheet.add(0x0809, bof(0x0010));
        sheet.add(0x020B, index.array());
        sheet.add(0x0055, buffer(2).putShort((short) 8).array());
        sheet.add(0x0200, buffer(14).putInt(0).putInt(all.size()).putShort((short) 0)
                .putShort((short) columns).putShort((short) 0).array());
        for (byte[] block : blocks) {
            sheet.raw(block);
        }
        // WINDOW2: siatka, nagłówki wierszy i kolumn, zera, arkusz zaznaczony i aktywny.
        sheet.add(0x023E, buffer(18).putShort((short) 0x06B6).putShort((short) 0).putShort((short) 0)
                .putShort((short) 64).putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).array());
        sheet.add(0x000A, new byte[0]);

        ByteArrayOutputStream workbook = new ByteArrayOutputStream();
        workbook.writeBytes(globals.bytes());
        workbook.writeBytes(sheet.bytes());
        return compoundFile(workbook.toByteArray());
    }

    private static Records globals(String sheetName, SharedStrings strings) {
        Records globals = new Records();
        globals.add(0x0809, bof(0x0005));
        globals.add(0x0042, buffer(2).putShort((short) 1200).array()); // CODEPAGE: UTF-16
        globals.add(0x003D, buffer(18).putShort((short) 0).putShort((short) 0).putShort((short) 0x3A5C)
                .putShort((short) 0x23BE).putShort((short) 0x0038).putShort((short) 0).putShort((short) 0)
                .putShort((short) 1).putShort((short) 0x0258).array());
        // Czcionka nr 4 jest w BIFF zarezerwowana, więc zapisujemy cztery takie same (0-3).
        for (int i = 0; i < 4; i++) {
            globals.add(0x0031, font("Arial"));
        }
        for (int i = 0; i < CELL_XF; i++) {
            globals.add(0x00E0, xf(true));
        }
        globals.add(0x00E0, xf(false));
        globals.add(0x0293, buffer(4).putShort((short) 0x8000).put((byte) 0).put((byte) 0xFF).array()); // styl Normal
        byte[] name = shortString(sheetName);
        int boundSheetSize = 4 + 4 + 2 + name.length;
        int sstAt = globals.size() + boundSheetSize;
        Records sst = strings.records(sstAt);
        int sheetOffset = sstAt + sst.size() + 4; // + EOF
        globals.add(0x0085, ByteBuffer.allocate(6 + name.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sheetOffset).put((byte) 0).put((byte) 0).put(name).array());
        globals.raw(sst.bytes());
        globals.add(0x000A, new byte[0]);
        return globals;
    }

    /** Wiersze [first, last): najpierw ich rekordy ROW, potem komórki wiersz po wierszu, na końcu DBCELL. */
    private static byte[] rowBlock(List<List<Object>> all, int first, int last, SharedStrings strings) {
        Records rowRecords = new Records();
        List<byte[]> cells = new ArrayList<>();
        for (int row = first; row < last; row++) {
            List<Object> values = all.get(row);
            Records rowCells = new Records();
            int firstCol = -1;
            int lastCol = -1;
            for (int col = 0; col < values.size(); col++) {
                if (cell(rowCells, row, col, values.get(col), strings)) {
                    firstCol = firstCol < 0 ? col : firstCol;
                    lastCol = col;
                }
            }
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
        if (text.isEmpty()) {
            return false;
        }
        if (text.length() > 255) {
            text = text.substring(0, 255);
        }
        sheet.add(0x00FD, buffer(10).putShort((short) row).putShort((short) col).putShort((short) CELL_XF)
                .putInt(strings.index(text)).array());
        return true;
    }

    /**
     * Liczba jako RK, jak zapisuje ją Excel: całkowita, całkowita setna część (ceny z groszami) albo double, którego
     * dolne 34 bity są zerowe; inaczej {@code null} i rekord NUMBER.
     */
    static Integer rk(double value) {
        int limit = 1 << 29;
        if (value == Math.rint(value) && Math.abs(value) < limit) {
            return (int) value << 2 | 0x2;
        }
        double hundredths = value * 100;
        long rounded = Math.round(hundredths);
        if (Math.abs(rounded) < limit && rounded / 100.0 == value) {
            return (int) rounded << 2 | 0x3;
        }
        long bits = Double.doubleToLongBits(value);
        if ((bits & 0x3_FFFF_FFFFL) == 0) {
            return (int) (bits >>> 32);
        }
        return null;
    }

    private static byte[] bof(int type) {
        return buffer(16).putShort((short) 0x0600).putShort((short) type).putShort((short) 0x0DBB)
                .putShort((short) 0x07CC).putInt(0).putInt(0x06).array();
    }

    private static byte[] font(String name) {
        byte[] text = shortString(name);
        return ByteBuffer.allocate(14 + text.length).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 200).putShort((short) 0).putShort((short) 0x7FFF).putShort((short) 400)
                .putShort((short) 0).put((byte) 0).put((byte) 0).put((byte) 0xEE).put((byte) 0).put(text).array();
    }

    /** XF stylu (bez rodzica) albo komórki (rodzic = styl Normal); wyrównanie do dołu, bez obramowań i wypełnienia. */
    private static byte[] xf(boolean style) {
        return buffer(20).putShort((short) 0).putShort((short) 0).putShort((short) (style ? 0xFFF5 : 0x0001))
                .put((byte) 0x20).put((byte) 0).put((byte) 0).put((byte) (style ? 0xF4 : 0))
                .putInt(0).putInt(0).putShort((short) 0x20C0).array();
    }

    /** Napis z 1-bajtową długością (nazwa arkusza, czcionki); zawsze UTF-16. */
    private static byte[] shortString(String text) {
        byte[] chars = text.getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(2 + chars.length).put((byte) text.length()).put((byte) 1).put(chars).array();
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

    /**
     * Kontener OLE (Compound File v3, sektory po 512 bajtów) z jednym strumieniem „Workbook”: nagłówek, sektory
     * strumienia, sektor katalogu i tablica FAT.
     */
    private static byte[] compoundFile(byte[] workbook) {
        int streamSize = Math.max(workbook.length, MIN_STREAM);
        int streamSectors = (streamSize + SECTOR - 1) / SECTOR;
        int fatSectors = 1;
        while ((streamSectors + 1 + fatSectors + 127) / 128 > fatSectors) {
            fatSectors++;
        }
        if (fatSectors > 109) {
            throw new IllegalArgumentException("Plik .xls jest za duży");
        }
        int directorySector = streamSectors;
        int firstFat = streamSectors + 1;

        ByteBuffer file = buffer(SECTOR * (1 + streamSectors + 1 + fatSectors));
        file.putLong(0xE11AB1A1E011CFD0L).put(new byte[16]).putShort((short) 0x003E).putShort((short) 0x0003)
                .putShort((short) 0xFFFE).putShort((short) 9).putShort((short) 6).put(new byte[6])
                .putInt(0).putInt(fatSectors).putInt(directorySector).putInt(0).putInt(MIN_STREAM)
                .putInt(END_OF_CHAIN).putInt(0).putInt(END_OF_CHAIN).putInt(0);
        for (int i = 0; i < 109; i++) {
            file.putInt(i < fatSectors ? firstFat + i : FREE);
        }

        file.position(SECTOR);
        file.put(workbook);

        file.position(SECTOR * (1 + directorySector));
        file.put(directoryEntry("Root Entry", 5, 1, END_OF_CHAIN, 0));
        file.put(directoryEntry("Workbook", 2, FREE, 0, streamSize));
        file.put(directoryEntry("", 0, FREE, 0, 0));
        file.put(directoryEntry("", 0, FREE, 0, 0));

        file.position(SECTOR * (1 + firstFat));
        for (int sector = 0; sector < fatSectors * 128; sector++) {
            int next;
            if (sector < streamSectors) {
                next = sector == streamSectors - 1 ? END_OF_CHAIN : sector + 1;
            } else if (sector == directorySector) {
                next = END_OF_CHAIN;
            } else if (sector < firstFat + fatSectors) {
                next = FAT_SECTOR;
            } else {
                next = FREE;
            }
            file.putInt(next);
        }
        return file.array();
    }

    private static byte[] directoryEntry(String name, int type, int child, int start, int size) {
        ByteBuffer entry = buffer(128);
        byte[] chars = name.getBytes(StandardCharsets.UTF_16LE);
        entry.put(chars).position(64);
        entry.putShort((short) (name.isEmpty() ? 0 : chars.length + 2)).put((byte) type).put((byte) 1)
                .putInt(FREE).putInt(FREE).putInt(child).put(new byte[16]).putInt(0).putLong(0).putLong(0)
                .putInt(start).putInt(size).putInt(0);
        return entry.array();
    }
}
