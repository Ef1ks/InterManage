package com.intermanage.backend.common.component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;

import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.common.exception.ApiException;

/**
 * Pliki wysyłane spakowane gzipem (agent sklepu na wolnym łączu: pliki cen z kasy i dziennik ATHOS to tekst, który
 * zmniejsza się kilka-kilkanaście razy). Gzip rozpoznajemy po pierwszych bajtach, końcówkę {@code .gz} zdejmujemy
 * z nazwy, a rozpakowany plik ma limit rozmiaru (ochrona przed „bombą” z małego pliku). Plik niespakowany wraca bez
 * zmian, więc ta sama ścieżka przyjmuje oba.
 */
public final class CompressedUploads {

    private CompressedUploads() {
    }

    /** Plik po rozpakowaniu (albo ten sam, gdy nie był spakowany). */
    public static MultipartFile unpacked(MultipartFile file, long maxBytes) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku");
        }
        if (!gzip(bytes)) {
            return file;
        }
        String name = file.getOriginalFilename() == null ? "plik" : file.getOriginalFilename();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".gz")) {
            name = name.substring(0, name.length() - 3);
        }
        return new BytesFile(file.getName(), name, inflate(bytes, maxBytes, name));
    }

    static boolean gzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
    }

    private static byte[] inflate(byte[] bytes, long maxBytes, String name) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(bytes.length * 8, 8 * 1024 * 1024));
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    throw ApiException.badRequest("Plik " + name + " po rozpakowaniu jest za duży");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się rozpakować pliku " + name);
        }
    }

    /** Plik w pamięci jako {@link MultipartFile} (serwisy importu przyjmują taki). */
    public static final class BytesFile implements MultipartFile {

        private final String name;
        private final String originalFilename;
        private final byte[] content;

        public BytesFile(String name, String originalFilename, byte[] content) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.content = content;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }
}
