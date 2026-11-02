package com.intermanage.backend.common.component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Najprostszy skoroszyt w starym formacie Excela (.xls, BIFF8 w kontenerze OLE) z jednym arkuszem, dla programów,
 * które nie czytają .xlsx. Bez Apache POI: pierwszy wiersz to nagłówek, teksty zapisujemy jako tekst (kody zachowują
 * zera wiodące), liczby ({@link Number}) jako liczby, {@code null} to pusta komórka.
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

    public byte[] write(String sheetName, List<String> headers, List<List<Object>> rows) {
        if (rows.size() + 1 > 65_536 || headers.size() > 256) {
            throw new IllegalArgumentException("Arkusz .xls ma najwyżej 65536 wierszy i 256 kolumn");
        }
        Records sheet = new Records();
        sheet.add(0x0809, bof(0x0010));
        int columns = Math.max(headers.size(), rows.stream().mapToInt(List::size).max().orElse(0));
        sheet.add(0x0200, buffer(14).putInt(0).putInt(rows.size() + 1).putShort((short) 0)
                .putShort((short) columns).putShort((short) 0).array());
        for (int col = 0; col < headers.size(); col++) {
            cell(sheet, 0, col, headers.get(col));
        }
        for (int row = 0; row < rows.size(); row++) {
            List<Object> cells = rows.get(row);
            for (int col = 0; col < cells.size(); col++) {
                cell(sheet, row + 1, col, cells.get(col));
            }
        }
        // WINDOW2: siatka, nagłówki wierszy i kolumn, zera, arkusz zaznaczony i aktywny.
        sheet.add(0x023E, buffer(18).putShort((short) 0x06B6).putShort((short) 0).putShort((short) 0)
                .putShort((short) 64).putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).array());
        sheet.add(0x000A, new byte[0]);

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
        int sheetOffset = globals.size() + boundSheetSize + 4; // + EOF
        globals.add(0x0085, ByteBuffer.allocate(6 + name.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sheetOffset).put((byte) 0).put((byte) 0).put(name).array());
        globals.add(0x000A, new byte[0]);

        ByteArrayOutputStream workbook = new ByteArrayOutputStream();
        workbook.writeBytes(globals.bytes());
        workbook.writeBytes(sheet.bytes());
        return compoundFile(workbook.toByteArray());
    }

    private static void cell(Records sheet, int row, int col, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            double numeric = number instanceof BigDecimal decimal ? decimal.doubleValue() : number.doubleValue();
            sheet.add(0x0203, buffer(14).putShort((short) row).putShort((short) col).putShort((short) CELL_XF)
                    .putDouble(numeric).array());
            return;
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > 255) {
            text = text.substring(0, 255);
        }
        byte[] string = unicodeString(text);
        sheet.add(0x0204, ByteBuffer.allocate(6 + string.length).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) row).putShort((short) col).putShort((short) CELL_XF).put(string).array());
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

    /** Napis komórki z 2-bajtową długością, zawsze UTF-16 (polskie znaki w nagłówkach). */
    private static byte[] unicodeString(String text) {
        byte[] chars = text.getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(3 + chars.length).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) text.length()).put((byte) 1).put(chars).array();
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Rekordy BIFF: typ, długość, dane. */
    private static final class Records {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void add(int type, byte[] data) {
            out.writeBytes(buffer(4).putShort((short) type).putShort((short) data.length).array());
            out.writeBytes(data);
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
