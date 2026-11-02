package com.intermanage.backend.common.component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.exception.InvalidWorkbookException;

/**
 * Pierwszy arkusz skoroszytu w starym formacie Excela (.xls, BIFF8 w kontenerze OLE), bez Apache POI; odwrotność
 * {@link XlsWriter}. Teksty jak w pliku (kody z zerami wiodącymi), liczby jako tekst bez zbędnych zer ({@code 1},
 * {@code 0.42}), pusta komórka to {@code null}. Daty zapisane jako liczby zostają numerem dnia Excela (pliki, które
 * czytamy, mają daty tekstem).
 */
@Component
public class XlsReader {

    private static final long SIGNATURE = 0xE11AB1A1E011CFD0L;
    private static final int END_OF_CHAIN = 0xFFFFFFFE;
    private static final int FREE = 0xFFFFFFFF;
    /** Ochrona przed zapętlonym łańcuchem sektorów w uszkodzonym pliku. */
    private static final int MAX_SECTORS = 1 << 20;

    private static final int BOF = 0x0809;
    private static final int EOF = 0x000A;
    private static final int CONTINUE = 0x003C;
    private static final int SST = 0x00FC;
    private static final int BOUNDSHEET = 0x0085;
    private static final int LABELSST = 0x00FD;
    private static final int LABEL = 0x0204;
    private static final int RSTRING = 0x00D6;
    private static final int NUMBER = 0x0203;
    private static final int RK = 0x027E;
    private static final int MULRK = 0x00BD;
    private static final int FORMULA = 0x0006;
    private static final int STRING = 0x0207;
    private static final int BOOLERR = 0x0205;

    /** Wiersze pierwszego arkusza; {@code rows.get(r).get(c)} to komórka (A1 = 0, 0), krótsze wiersze bez pustych na końcu. */
    public List<List<String>> readFirstSheet(byte[] file) throws InvalidWorkbookException {
        try {
            return sheet(workbookStream(file));
        } catch (RuntimeException e) {
            throw new InvalidWorkbookException("Nie udało się odczytać pliku .xls: " + e.getMessage(), e);
        }
    }

    // ---------- kontener OLE (Compound File)

    private static byte[] workbookStream(byte[] file) throws InvalidWorkbookException {
        ByteBuffer data = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (file.length < 512 || data.getLong(0) != SIGNATURE) {
            throw new InvalidWorkbookException("To nie jest plik Excela .xls");
        }
        int sectorSize = 1 << data.getShort(0x1E);
        int miniSectorSize = 1 << data.getShort(0x20);
        int firstDirectory = data.getInt(0x30);
        int miniCutoff = data.getInt(0x38);
        int firstMiniFat = data.getInt(0x3C);
        int firstDifat = data.getInt(0x44);
        int difatCount = data.getInt(0x48);

        List<Integer> fatSectors = new ArrayList<>();
        for (int i = 0; i < 109; i++) {
            int sector = data.getInt(0x4C + i * 4);
            if (sector != FREE && sector != END_OF_CHAIN) {
                fatSectors.add(sector);
            }
        }
        int difat = firstDifat;
        for (int n = 0; n < difatCount && difat != END_OF_CHAIN && difat != FREE; n++) {
            int base = offset(difat, sectorSize);
            int entries = sectorSize / 4 - 1;
            for (int i = 0; i < entries; i++) {
                int sector = data.getInt(base + i * 4);
                if (sector != FREE && sector != END_OF_CHAIN) {
                    fatSectors.add(sector);
                }
            }
            difat = data.getInt(base + entries * 4);
        }
        int[] fat = new int[fatSectors.size() * (sectorSize / 4)];
        for (int i = 0; i < fatSectors.size(); i++) {
            int base = offset(fatSectors.get(i), sectorSize);
            for (int j = 0; j < sectorSize / 4; j++) {
                fat[i * (sectorSize / 4) + j] = data.getInt(base + j * 4);
            }
        }

        byte[] directory = chain(file, fat, firstDirectory, sectorSize, -1);
        ByteBuffer entries = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN);
        int rootStart = END_OF_CHAIN;
        long rootSize = 0;
        for (int at = 0; at + 128 <= directory.length; at += 128) {
            int nameLength = entries.getShort(at + 64);
            int type = entries.get(at + 66);
            int start = entries.getInt(at + 116);
            long size = entries.getInt(at + 120) & 0xFFFFFFFFL;
            if (type == 5) {
                rootStart = start;
                rootSize = size;
                continue;
            }
            if (type != 2 || nameLength < 2) {
                continue;
            }
            String name = new String(directory, at, Math.min(nameLength - 2, 64), StandardCharsets.UTF_16LE);
            if (!name.equals("Workbook") && !name.equals("Book")) {
                continue;
            }
            if (size >= miniCutoff) {
                return chain(file, fat, start, sectorSize, size);
            }
            // Mały strumień leży w mini strumieniu (strumień katalogu głównego) w sektorach po 64 bajty.
            byte[] miniStream = chain(file, fat, rootStart, sectorSize, rootSize);
            byte[] miniFatBytes = chain(file, fat, firstMiniFat, sectorSize, -1);
            ByteBuffer miniFatData = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN);
            int[] miniFat = new int[miniFatBytes.length / 4];
            for (int i = 0; i < miniFat.length; i++) {
                miniFat[i] = miniFatData.getInt(i * 4);
            }
            return miniChain(miniStream, miniFat, start, miniSectorSize, size);
        }
        throw new InvalidWorkbookException("Plik .xls nie ma skoroszytu");
    }

    private static int offset(int sector, int sectorSize) {
        return (sector + 1) * sectorSize;
    }

    /** Strumień z łańcucha sektorów; {@code size} < 0 = cały łańcuch. */
    private static byte[] chain(byte[] file, int[] fat, int start, int sectorSize, long size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int sector = start;
        int count = 0;
        while (sector != END_OF_CHAIN && sector != FREE) {
            if (sector < 0 || sector >= fat.length || ++count > MAX_SECTORS) {
                throw new IllegalStateException("uszkodzony łańcuch sektorów");
            }
            int from = offset(sector, sectorSize);
            out.write(file, from, Math.min(sectorSize, file.length - from));
            sector = fat[sector];
        }
        byte[] bytes = out.toByteArray();
        return size >= 0 && size < bytes.length ? Arrays.copyOf(bytes, (int) size) : bytes;
    }

    private static byte[] miniChain(byte[] stream, int[] miniFat, int start, int sectorSize, long size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int sector = start;
        int count = 0;
        while (sector != END_OF_CHAIN && sector != FREE && out.size() < size) {
            if (sector < 0 || sector >= miniFat.length || ++count > MAX_SECTORS) {
                throw new IllegalStateException("uszkodzony łańcuch mini sektorów");
            }
            out.write(stream, sector * sectorSize, Math.min(sectorSize, stream.length - sector * sectorSize));
            sector = miniFat[sector];
        }
        return Arrays.copyOf(out.toByteArray(), (int) size);
    }

    // ---------- rekordy BIFF8

    private record Record(int type, byte[] data, List<byte[]> continues) {
    }

    private static List<List<String>> sheet(byte[] workbook) throws InvalidWorkbookException {
        ByteBuffer in = ByteBuffer.wrap(workbook).order(ByteOrder.LITTLE_ENDIAN);
        List<String> strings = List.of();
        int sheetOffset = -1;
        int position = 0;
        boolean first = true;
        while (position + 4 <= workbook.length) {
            Record record = record(in, position);
            position = next(in, position);
            if (first) {
                if (record.type() != BOF || in.getShort(4) != 0x0600) {
                    throw new InvalidWorkbookException("Obsługujemy pliki .xls z Excela 97 i nowszego");
                }
                first = false;
                continue;
            }
            if (record.type() == SST) {
                strings = sharedStrings(record);
            } else if (record.type() == BOUNDSHEET && sheetOffset < 0 && (record.data()[5] & 0xFF) == 0) {
                sheetOffset = ByteBuffer.wrap(record.data()).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            } else if (record.type() == EOF) {
                break;
            }
        }
        if (sheetOffset < 0 || sheetOffset >= workbook.length) {
            throw new InvalidWorkbookException("Plik .xls nie ma arkusza");
        }

        Cells cells = new Cells();
        position = sheetOffset;
        int formulaRow = -1;
        int formulaCol = -1;
        // Podstrumień wewnątrz arkusza (np. wykres) ma własne BOF i EOF; komórki są tylko na poziomie 1.
        int depth = 0;
        while (position + 4 <= workbook.length) {
            Record record = record(in, position);
            position = next(in, position);
            ByteBuffer data = ByteBuffer.wrap(record.data()).order(ByteOrder.LITTLE_ENDIAN);
            if (record.type() == BOF) {
                depth++;
                continue;
            }
            if (record.type() == EOF) {
                if (--depth <= 0) {
                    return cells.rows();
                }
                continue;
            }
            if (depth > 1) {
                continue;
            }
            switch (record.type()) {
                case LABELSST -> {
                    int index = data.getInt(6);
                    cells.put(row(data), col(data), index >= 0 && index < strings.size() ? strings.get(index) : null);
                }
                case LABEL, RSTRING -> cells.put(row(data), col(data), new Reader(record, 6).string(2));
                case NUMBER -> cells.put(row(data), col(data), number(data.getDouble(6)));
                case RK -> cells.put(row(data), col(data), number(rk(data.getInt(6))));
                case MULRK -> {
                    int row = row(data);
                    int firstCol = col(data);
                    int count = (record.data().length - 6) / 6;
                    for (int i = 0; i < count; i++) {
                        cells.put(row, firstCol + i, number(rk(data.getInt(4 + i * 6 + 2))));
                    }
                }
                case BOOLERR -> {
                    if (record.data()[7] == 0) {
                        cells.put(row(data), col(data), record.data()[6] != 0 ? "TRUE" : "FALSE");
                    }
                }
                case FORMULA -> {
                    if ((data.getShort(12) & 0xFFFF) == 0xFFFF) {
                        int kind = record.data()[6];
                        if (kind == 0) {
                            formulaRow = row(data);
                            formulaCol = col(data);
                        } else if (kind == 1) {
                            cells.put(row(data), col(data), record.data()[8] != 0 ? "TRUE" : "FALSE");
                        }
                    } else {
                        cells.put(row(data), col(data), number(data.getDouble(6)));
                    }
                }
                case STRING -> {
                    if (formulaRow >= 0) {
                        cells.put(formulaRow, formulaCol, new Reader(record, 0).string(2));
                        formulaRow = -1;
                    }
                }
                default -> {
                    // Formatowanie, wymiary, okno itp. nie zmieniają wartości komórek.
                }
            }
        }
        return cells.rows();
    }

    private static int row(ByteBuffer data) {
        return data.getShort(0) & 0xFFFF;
    }

    private static int col(ByteBuffer data) {
        return data.getShort(2) & 0xFFFF;
    }

    private static Record record(ByteBuffer in, int position) {
        int type = in.getShort(position) & 0xFFFF;
        int length = in.getShort(position + 2) & 0xFFFF;
        byte[] data = new byte[length];
        in.get(position + 4, data);
        List<byte[]> continues = new ArrayList<>();
        int at = position + 4 + length;
        while (at + 4 <= in.capacity() && (in.getShort(at) & 0xFFFF) == CONTINUE) {
            int size = in.getShort(at + 2) & 0xFFFF;
            byte[] part = new byte[size];
            in.get(at + 4, part);
            continues.add(part);
            at += 4 + size;
        }
        return new Record(type, data, continues);
    }

    /** Pozycja kolejnego rekordu (CONTINUE należą do poprzedniego). */
    private static int next(ByteBuffer in, int position) {
        int at = position + 4 + (in.getShort(position + 2) & 0xFFFF);
        while (at + 4 <= in.capacity() && (in.getShort(at) & 0xFFFF) == CONTINUE) {
            at += 4 + (in.getShort(at + 2) & 0xFFFF);
        }
        return at;
    }

    private static List<String> sharedStrings(Record record) {
        Reader reader = new Reader(record, 0);
        reader.skip(4);
        int unique = reader.int32();
        List<String> strings = new ArrayList<>(Math.max(0, Math.min(unique, 1 << 20)));
        for (int i = 0; i < unique && reader.hasMore(); i++) {
            strings.add(reader.string(2));
        }
        return strings;
    }

    private static double rk(int value) {
        double number = (value & 0x02) != 0
                ? value >> 2
                : Double.longBitsToDouble(((long) (value & 0xFFFFFFFC)) << 32);
        return (value & 0x01) != 0 ? number / 100 : number;
    }

    private static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        String text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return text.equals("-0") ? "0" : text;
    }

    /**
     * Odczyt danych rekordu razem z jego rekordami CONTINUE. Napis przecięty granicą CONTINUE ma w nowej części
     * własny bajt opcji (1 = UTF-16, 0 = 1 bajt na znak).
     */
    private static final class Reader {

        private final List<byte[]> parts = new ArrayList<>();
        private int part;
        private int at;

        Reader(Record record, int start) {
            parts.add(record.data());
            parts.addAll(record.continues());
            at = start;
        }

        boolean hasMore() {
            settle();
            return part < parts.size() && at < parts.get(part).length;
        }

        private void settle() {
            while (part < parts.size() && at >= parts.get(part).length && part + 1 < parts.size()) {
                at -= parts.get(part).length;
                part++;
            }
        }

        int u8() {
            settle();
            return parts.get(part)[at++] & 0xFF;
        }

        int u16() {
            return u8() | (u8() << 8);
        }

        int int32() {
            return u16() | (u16() << 16);
        }

        void skip(int count) {
            for (int i = 0; i < count; i++) {
                u8();
            }
        }

        /** Napis BIFF8 z długością na {@code lengthBytes} bajtach, z pominięciem formatowania i danych azjatyckich. */
        String string(int lengthBytes) {
            int length = lengthBytes == 2 ? u16() : u8();
            int options = u8();
            boolean wide = (options & 0x01) != 0;
            int runs = (options & 0x08) != 0 ? u16() : 0;
            int extended = (options & 0x04) != 0 ? int32() : 0;
            StringBuilder text = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                settle();
                if (at == 0 && part > 0 && i > 0) {
                    wide = (u8() & 0x01) != 0;
                }
                text.append(wide ? (char) u16() : (char) u8());
            }
            skip(runs * 4 + extended);
            return text.toString();
        }
    }

    /** Komórki zebrane w wiersze; brakujące to {@code null}. */
    private static final class Cells {

        private final List<List<String>> rows = new ArrayList<>();

        void put(int row, int col, String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            while (rows.size() <= row) {
                rows.add(new ArrayList<>());
            }
            List<String> cells = rows.get(row);
            while (cells.size() <= col) {
                cells.add(null);
            }
            cells.set(col, value);
        }

        List<List<String>> rows() {
            return rows;
        }
    }
}
