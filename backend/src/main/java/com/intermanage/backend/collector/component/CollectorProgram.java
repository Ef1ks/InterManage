package com.intermanage.backend.collector.component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.springframework.stereotype.Component;

/**
 * Program kolektora w zasobach backendu ({@code resources/collector}, składa go {@code collector/build.ps1}): instalator
 * i pliki wersji. Wdrożenie z nowszą wersją = kolektory pobiorą ją na podstawce. Bez plików nie ma czego pobierać.
 */
@Component
public class CollectorProgram {

    public static final String SETUP = "IntermanageSetup.exe";

    private static final String FOLDER = "/collector/";

    /** Plik wersji programu. */
    public record ProgramFile(byte[] content, String sha256) {
    }

    private final String version;
    private final Map<String, ProgramFile> files;
    private final byte[] setup;

    public CollectorProgram() {
        Map<String, ProgramFile> loaded = new LinkedHashMap<>();
        String found = null;
        byte[] properties = read("program.properties");
        if (properties != null) {
            Properties description = new Properties();
            try {
                description.load(new java.io.ByteArrayInputStream(properties));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            found = description.getProperty("version");
            for (String name : description.getProperty("files", "").split(",")) {
                byte[] content = read(name);
                if (content == null) {
                    throw new IllegalStateException("Brak pliku programu kolektora: " + name);
                }
                loaded.put(name, new ProgramFile(content, sha256(content)));
            }
        }
        version = found;
        files = Map.copyOf(loaded);
        setup = read(SETUP);
    }

    /** Wersja do pobrania przez kolektory. */
    public Optional<String> version() {
        return Optional.ofNullable(version);
    }

    public Map<String, ProgramFile> files() {
        return files;
    }

    public Optional<byte[]> setup() {
        return Optional.ofNullable(setup);
    }

    private static byte[] read(String name) {
        try (InputStream stream = CollectorProgram.class.getResourceAsStream(FOLDER + name)) {
            return stream == null ? null : stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
