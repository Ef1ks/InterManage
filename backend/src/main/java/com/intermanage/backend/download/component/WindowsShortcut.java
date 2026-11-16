package com.intermanage.backend.download.component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Skrót Windows (.lnk, [MS-SHLLINK]): rozpoznanie po nagłówku i folder, do którego prowadzi. Folder jest w bloku
 * LinkInfo (dysk lokalny, dysk sieciowy z literą, ścieżka sieciowa serwera), a gdy go nie ma (skrót zrobiony na
 * komputerze bez tego dysku, np. N:), w liście elementów powłoki: litera dysku i nazwy kolejnych folderów. Czego się
 * nie da odczytać, zostaje puste; skrót dalej da się pobrać.
 */
public final class WindowsShortcut {

    private static final int HEADER_SIZE = 0x4C;
    private static final byte[] LINK_CLSID = {0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xC0, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x46};
    /** „Ten komputer” (20D04FE0-3AEA-1069-A2D8-08002B30309D) jako pierwszy element listy. */
    private static final byte[] MY_COMPUTER = {(byte) 0xE0, 0x4F, (byte) 0xD0, 0x20, (byte) 0xEA, 0x3A, 0x69, 0x10,
            (byte) 0xA2, (byte) 0xD8, 0x08, 0x00, 0x2B, 0x30, 0x30, (byte) 0x9D};

    private static final int HAS_ID_LIST = 0x01;
    private static final int HAS_LINK_INFO = 0x02;
    private static final int LOCAL_PATH = 0x01;
    private static final int NETWORK_PATH = 0x02;
    private static final int VALID_DEVICE = 0x01;

    /** Kody ANSI w skrócie są w stronie kodowej systemu, w Polsce windows-1250. */
    private static final Charset ANSI = Charset.forName("windows-1250");

    private WindowsShortcut() {
    }

    public static boolean isShortcut(byte[] content) {
        return content.length >= HEADER_SIZE && le(content).getInt(0) == HEADER_SIZE
                && Arrays.equals(content, 4, 20, LINK_CLSID, 0, 16);
    }

    /** Folder (albo plik) docelowy skrótu. */
    public static Optional<String> target(byte[] content) {
        if (!isShortcut(content)) {
            return Optional.empty();
        }
        try {
            ByteBuffer data = le(content);
            int flags = data.getInt(0x14);
            int position = HEADER_SIZE;
            String fromIdList = null;
            if ((flags & HAS_ID_LIST) != 0) {
                int size = Short.toUnsignedInt(data.getShort(position));
                fromIdList = idListPath(content, position + 2, position + 2 + size);
                position += 2 + size;
            }
            if ((flags & HAS_LINK_INFO) != 0) {
                String fromLinkInfo = linkInfoPath(content, position);
                if (fromLinkInfo != null) {
                    return Optional.of(fromLinkInfo);
                }
            }
            return Optional.ofNullable(fromIdList);
        } catch (RuntimeException e) {
            // Uszkodzony albo nietypowy skrót: bez folderu, ale plik zostaje.
            return Optional.empty();
        }
    }

    private static String linkInfoPath(byte[] content, int start) {
        ByteBuffer data = le(content);
        int headerSize = data.getInt(start + 4);
        int infoFlags = data.getInt(start + 8);
        int localBase = data.getInt(start + 16);
        int network = data.getInt(start + 20);
        int suffixOffset = data.getInt(start + 24);
        boolean unicode = headerSize >= 0x24;
        String suffix = unicode && data.getInt(start + 32) != 0
                ? utf16(content, start + data.getInt(start + 32))
                : ansi(content, start + suffixOffset);
        if ((infoFlags & LOCAL_PATH) != 0) {
            String base = unicode && data.getInt(start + 28) != 0
                    ? utf16(content, start + data.getInt(start + 28))
                    : ansi(content, start + localBase);
            return join(base, suffix);
        }
        if ((infoFlags & NETWORK_PATH) != 0) {
            int link = start + network;
            int linkFlags = data.getInt(link + 4);
            int netNameOffset = data.getInt(link + 8);
            int deviceOffset = data.getInt(link + 12);
            boolean linkUnicode = netNameOffset > 0x14;
            String device = (linkFlags & VALID_DEVICE) != 0
                    ? (linkUnicode ? utf16(content, link + data.getInt(link + 24)) : ansi(content, link + deviceOffset))
                    : "";
            String netName = linkUnicode ? utf16(content, link + data.getInt(link + 20))
                    : ansi(content, link + netNameOffset);
            // Dysk sieciowy z literą pracownik zna jako P:\…, a nie jako ścieżkę sieciową serwera.
            return join(device.isEmpty() ? netName : device, suffix);
        }
        return null;
    }

    /** „Ten komputer” → dysk → foldery; inny początek (np. folder użytkownika) to brak ścieżki. */
    private static String idListPath(byte[] content, int start, int end) {
        ByteBuffer data = le(content);
        List<String> parts = new ArrayList<>();
        int position = start;
        while (position + 2 <= end) {
            int size = Short.toUnsignedInt(data.getShort(position));
            if (size == 0) {
                break;
            }
            int type = content[position + 2] & 0xFF;
            if (parts.isEmpty() && type == 0x1F) {
                if (!Arrays.equals(content, position + 4, position + 20, MY_COMPUTER, 0, 16)) {
                    return null;
                }
            } else if (type >= 0x20 && type <= 0x2F) {
                String drive = ansi(content, position + 3);
                if (!drive.matches("[A-Za-z]:\\\\?")) {
                    return null;
                }
                parts.clear();
                parts.add(drive.substring(0, 2));
            } else if ((type & 0x70) == 0x30 && !parts.isEmpty()) {
                String name = fileEntryName(content, position, size, (type & 0x04) != 0);
                if (name == null || name.isEmpty()) {
                    return null;
                }
                parts.add(name);
            } else {
                return null;
            }
            position += size;
        }
        if (parts.isEmpty()) {
            return null;
        }
        return parts.size() == 1 ? parts.get(0) + "\\" : String.join("\\", parts);
    }

    /** Długa nazwa z bloku rozszerzenia 0xBEEF0004, bez niego krótka nazwa 8.3. */
    private static String fileEntryName(byte[] content, int item, int size, boolean unicodeShortName) {
        int nameStart = item + 14;
        String shortName = unicodeShortName ? utf16(content, nameStart) : ansi(content, nameStart);
        int end = item + size;
        for (int i = nameStart; i + 8 <= end; i++) {
            if (content[i + 4] == 0x04 && content[i + 5] == 0x00 && content[i + 6] == (byte) 0xEF
                    && content[i + 7] == (byte) 0xBE) {
                int version = Short.toUnsignedInt(le(content).getShort(i + 2));
                int offset = version >= 9 ? 46 : version >= 8 ? 42 : version >= 7 ? 38 : 20;
                if (i + offset < end) {
                    String longName = utf16(content, i + offset);
                    if (!longName.isEmpty()) {
                        return longName;
                    }
                }
                break;
            }
        }
        return shortName;
    }

    private static String join(String base, String suffix) {
        if (base == null || base.isEmpty()) {
            return null;
        }
        if (suffix == null || suffix.isEmpty()) {
            return base;
        }
        return base.endsWith("\\") ? base + suffix : base + "\\" + suffix;
    }

    private static String ansi(byte[] content, int start) {
        int end = start;
        while (end < content.length && content[end] != 0) {
            end++;
        }
        return new String(content, start, end - start, ANSI);
    }

    private static String utf16(byte[] content, int start) {
        int end = start;
        while (end + 1 < content.length && (content[end] != 0 || content[end + 1] != 0)) {
            end += 2;
        }
        return new String(content, start, end - start, StandardCharsets.UTF_16LE);
    }

    private static ByteBuffer le(byte[] content) {
        return ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
    }
}
