package com.intermanage.backend.download.component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.intermanage.backend.common.exception.ApiException;

/**
 * Paczka agenta sklepu do automatycznej aktualizacji: ZIP ze zbudowanym folderem agenta ({@code bin\Release\net48}),
 * z {@code IntermanageAgent.exe} w głównym folderze archiwum albo w jednym folderze (spakowany cały folder). Wersja
 * jak w {@code Intermanage.Agent.csproj} (np. 1.5.0, może być z „v”), bo agent porównuje ją ze swoją.
 */
public final class AgentPackage {

    public static final String EXE = "IntermanageAgent.exe";

    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    private AgentPackage() {
    }

    /** Wersja bez „v” (1.5.0) albo null, gdy to nie jest numer wersji agenta. */
    public static String version(String version) {
        if (version == null) {
            return null;
        }
        String clean = version.strip();
        return VERSION.matcher(clean).matches() ? clean.replaceFirst("^v", "") : null;
    }

    /** Sprawdza paczkę przed zapisaniem jej jako aktualizacji agentów (400 z powodem). */
    public static void check(byte[] content, String version) {
        if (version(version) == null) {
            throw ApiException.badRequest(
                    "Paczka agenta wymaga wersji jak w Intermanage.Agent.csproj, np. 1.5.0 (agent porównuje ją ze swoją)");
        }
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.contains(":")) {
                    throw ApiException.badRequest("Paczka agenta ma niedozwoloną ścieżkę: " + name);
                }
                String[] parts = name.split("/");
                if (!entry.isDirectory() && parts[parts.length - 1].equalsIgnoreCase(EXE) && parts.length <= 2) {
                    found = true;
                }
            }
            if (entries == 0) {
                throw ApiException.badRequest("Paczka agenta musi być archiwum ZIP z folderem agenta");
            }
        } catch (IOException e) {
            throw ApiException.badRequest("Paczka agenta musi być archiwum ZIP z folderem agenta");
        }
        if (!found) {
            throw ApiException.badRequest("W paczce agenta brakuje " + EXE
                    + " (spakuj zawartość folderu bin\\Release\\net48)");
        }
    }
}
