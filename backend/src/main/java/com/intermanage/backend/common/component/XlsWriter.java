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
 * RK, a kilka kolejnych jako MULRK, wiersze ROW w blokach po 32 z DBCELL i INDEX, rekordy ustawień skoroszytu
 * i arkusza, CLSID Excela przy katalogu głównym i strumienie SummaryInformation / DocumentSummaryInformation), bo
 * program sklepu nie wczytywał „chudszego” pliku, dopóki nie otworzyło się go i nie zapisało w Excelu.
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

        // Arkusz: BOF, INDEX (pozycje DBCELL w strumieniu), ustawienia arkusza jak w Excelu, DEFCOLWIDTH, DIMENSIONS,
        // bloki wierszy, WINDOW2, SELECTION, EOF.
        Records settings = sheetSettings();
        int indexSize = 4 + 16 + 4 * blocks.size();
        int defColWidthAt = sheetStart + 20 + indexSize + settings.size();
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
        sheet.raw(settings.bytes());
        sheet.add(0x0055, buffer(2).putShort((short) 8).array());
        sheet.add(0x0200, buffer(14).putInt(0).putInt(all.size()).putShort((short) 0)
                .putShort((short) columns).putShort((short) 0).array());
        for (byte[] block : blocks) {
            sheet.raw(block);
        }
        // WINDOW2: siatka, nagłówki wierszy i kolumn, zera, arkusz zaznaczony i aktywny; SELECTION: kursor w A1.
        sheet.add(0x023E, hex("b6060000000040000000000000000f000000"));
        sheet.add(0x001D, hex("030000000000000100000000000000"));
        sheet.add(0x000A, new byte[0]);

        ByteArrayOutputStream workbook = new ByteArrayOutputStream();
        workbook.writeBytes(globals.bytes());
        workbook.writeBytes(sheet.bytes());
        return compoundFile(workbook.toByteArray());
    }

    /**
     * Rekordy skoroszytu w kolejności i z wartościami jak w pliku zapisanym przez Excela (wzór {@code CADBEZP-IMPEK.xls}):
     * program sklepu czyta plik ściślej niż Excel i bez nich wymagał otwarcia i zapisania pliku w Excelu.
     */
    private static Records globals(String sheetName, SharedStrings strings) {
        Records globals = new Records();
        globals.add(0x0809, bof(0x0005));
        globals.add(0x00E1, buffer(2).putShort((short) 1200).array()); // INTERFACEHDR
        globals.add(0x00C1, new byte[2]); // MMS
        globals.add(0x00E2, new byte[0]); // INTERFACEEND
        globals.add(0x005C, writeAccess("Intermanage"));
        globals.add(0x0042, buffer(2).putShort((short) 1200).array()); // CODEPAGE: UTF-16
        globals.add(0x0161, new byte[2]); // DSF
        globals.add(0x013D, buffer(2).putShort((short) 1).array()); // TABID: jeden arkusz
        globals.add(0x009C, buffer(2).putShort((short) 0x0E).array()); // FNGROUPCOUNT
        for (int type : new int[] {0x0019, 0x0012, 0x0013, 0x01AF, 0x01BC}) {
            globals.add(type, new byte[2]); // bez ochrony okna, skoroszytu i hasła
        }
        globals.add(0x003D, buffer(18).putShort((short) 0).putShort((short) 0).putShort((short) 0x3A5C)
                .putShort((short) 0x23BE).putShort((short) 0x0038).putShort((short) 0).putShort((short) 0)
                .putShort((short) 1).putShort((short) 0x0258).array());
        globals.add(0x0040, new byte[2]); // BACKUP
        globals.add(0x008D, new byte[2]); // HIDEOBJ
        globals.add(0x0022, new byte[2]); // DATEMODE: daty od 1900
        globals.add(0x000E, buffer(2).putShort((short) 1).array()); // PRECISION
        globals.add(0x01B7, new byte[2]); // REFRESHALL
        globals.add(0x00DA, new byte[2]); // BOOKBOOL
        // Czcionka nr 4 jest w BIFF zarezerwowana, więc zapisujemy cztery takie same (0-3).
        for (int i = 0; i < 4; i++) {
            globals.add(0x0031, font("Arial"));
        }
        for (int i = 0; i < CELL_XF; i++) {
            globals.add(0x00E0, xf(true));
        }
        globals.add(0x00E0, xf(false));
        globals.add(0x0293, buffer(4).putShort((short) 0x8000).put((byte) 0).put((byte) 0xFF).array()); // styl Normal
        globals.add(0x0160, new byte[2]); // USESELFS
        byte[] name = shortString(sheetName);
        int boundSheetSize = 4 + 4 + 2 + name.length;
        int countrySize = 4 + 4;
        int sstAt = globals.size() + boundSheetSize + countrySize;
        Records sst = strings.records(sstAt);
        int sheetOffset = sstAt + sst.size() + 4; // + EOF
        globals.add(0x0085, ByteBuffer.allocate(6 + name.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sheetOffset).put((byte) 0).put((byte) 0).put(name).array());
        globals.add(0x008C, buffer(4).putShort((short) 48).putShort((short) 48).array()); // COUNTRY: Polska
        globals.raw(sst.bytes());
        globals.add(0x000A, new byte[0]);
        return globals;
    }

    /** Ustawienia arkusza między INDEX a DEFCOLWIDTH: przeliczanie, druk i marginesy jak w Excelu. */
    private static Records sheetSettings() {
        Records settings = new Records();
        settings.add(0x000D, buffer(2).putShort((short) 1).array()); // CALCMODE: automatycznie
        settings.add(0x000C, buffer(2).putShort((short) 100).array()); // CALCCOUNT
        settings.add(0x000F, buffer(2).putShort((short) 1).array()); // REFMODE: A1
        settings.add(0x0011, new byte[2]); // ITERATION
        settings.add(0x0010, buffer(8).putDouble(0.001).array()); // DELTA
        settings.add(0x005F, buffer(2).putShort((short) 1).array()); // SAVERECALC
        settings.add(0x002A, new byte[2]); // PRINTHEADERS
        settings.add(0x002B, new byte[2]); // PRINTGRIDLINES
        settings.add(0x0082, buffer(2).putShort((short) 1).array()); // GRIDSET
        settings.add(0x0080, new byte[8]); // GUTS
        settings.add(0x0225, buffer(4).putShort((short) 0).putShort((short) ROW_HEIGHT).array()); // DEFAULTROWHEIGHT
        settings.add(0x0081, buffer(2).putShort((short) 0x04C1).array()); // WSBOOL
        settings.add(0x0014, new byte[0]); // HEADER
        settings.add(0x0015, new byte[0]); // FOOTER
        settings.add(0x0083, new byte[2]); // HCENTER
        settings.add(0x0084, new byte[2]); // VCENTER
        settings.add(0x0026, buffer(8).putDouble(0.7).array()); // LEFTMARGIN
        settings.add(0x0027, buffer(8).putDouble(0.7).array()); // RIGHTMARGIN
        settings.add(0x0028, buffer(8).putDouble(0.75).array()); // TOPMARGIN
        settings.add(0x0029, buffer(8).putDouble(0.75).array()); // BOTTOMMARGIN
        settings.add(0x00A1, hex("00002c01010001000100040042e50100333333333333d33f333333333333d33f0000")); // SETUP
        return settings;
    }

    /** WRITEACCESS: nazwa użytkownika, który zapisał plik, dopełniona spacjami do 112 bajtów. */
    private static byte[] writeAccess(String user) {
        byte[] record = new byte[112];
        java.util.Arrays.fill(record, (byte) ' ');
        byte[] chars = user.getBytes(StandardCharsets.ISO_8859_1);
        buffer(3).putShort((short) chars.length).put((byte) 0).flip().get(record, 0, 3);
        System.arraycopy(chars, 0, record, 3, chars.length);
        return record;
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

    /** CLSID skoroszytu Excela 97-2003 (00020820-0000-0000-C000-000000000046) przy katalogu głównym, jak w Excelu. */
    private static final String EXCEL_CLSID = "2008020000000000c000000000000046";
    private static final String SUMMARY_FMTID = "e0859ff2f94f6810ab9108002b27b3d9";
    private static final String DOCUMENT_SUMMARY_FMTID = "02d5cdd59c2e1b10939708002b2cf9ae";

    /**
     * Kontener OLE (Compound File v3, sektory po 512 bajtów) jak z Excela: strumienie „Workbook”,
     * „\u0005SummaryInformation” i „\u0005DocumentSummaryInformation” (każdy co najmniej 4096 bajtów, więc bez mini
     * strumienia), sektor katalogu i tablica FAT.
     */
    private static byte[] compoundFile(byte[] workbook) {
        List<byte[]> streams = List.of(workbook, propertySet(SUMMARY_FMTID), propertySet(DOCUMENT_SUMMARY_FMTID));
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

    /** Zbiór właściwości dokumentu z jedną właściwością: strona kodowa 1200 (UTF-16), jak w plikach Excela. */
    private static byte[] propertySet(String fmtid) {
        return buffer(48 + 24).putShort((short) 0xFFFE).putShort((short) 0).putInt(0x00020006).put(new byte[16])
                .putInt(1).put(hex(fmtid)).putInt(48)
                .putInt(24).putInt(1).putInt(1).putInt(16).putShort((short) 2).putShort((short) 0)
                .putShort((short) 1200).putShort((short) 0).array();
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
