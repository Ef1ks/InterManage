package com.intermanage.backend.common.component;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.common.exception.ApiException;

/** Skan albo zdjęcie dokumentu z formularza (WZ mięsa i piekarni): rozmiar, typ z pierwszych bajtów i nazwa pliku. */
public final class ScanFiles {

    public static final long MAX_FILE_BYTES = 15L * 1024 * 1024;

    private ScanFiles() {
    }

    public static byte[] content(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik ze skanem albo zdjęciem dokumentu");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej 15 MB)");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku");
        }
    }

    /** Typ pliku z pierwszych bajtów, nie z nazwy; {@code null} = nieobsługiwany. */
    public static String contentType(byte[] content) {
        if (startsWith(content, 0, 0x25, 0x50, 0x44, 0x46)) {
            return "application/pdf";
        }
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(content, 0, 0x52, 0x49, 0x46, 0x46) && startsWith(content, 8, 0x57, 0x45, 0x42, 0x50)) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int offset, int... prefix) {
        if (content.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[offset + i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public static String fileName(MultipartFile file, String contentType) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        String extension = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
        if (name.isEmpty()) {
            name = "wz" + extension;
        }
        return name.length() > 255 ? name.substring(0, 255 - extension.length()) + extension : name;
    }
}
