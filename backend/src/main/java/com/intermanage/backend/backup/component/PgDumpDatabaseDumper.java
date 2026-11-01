package com.intermanage.backend.backup.component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code pg_dump --format=custom} przepuszczony przez {@code gpg --symmetric} (AES-256), bez pliku pośredniego:
 * na dysk trafia od razu zaszyfrowany zrzut. Odtworzenie: {@code gpg -d kopia.dump.gpg > baza.dump}, potem
 * {@code pg_restore}. Wersja pg_dump nie może być starsza niż serwer bazy (obraz backendu ma klienta 18).
 */
@Component
public class PgDumpDatabaseDumper implements DatabaseDumper {

    private static final Logger log = LoggerFactory.getLogger(PgDumpDatabaseDumper.class);
    private static final Duration TIMEOUT = Duration.ofHours(3);
    private static final int LOG_TAIL = 600;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String pgDump;
    private final String gpg;

    public PgDumpDatabaseDumper(@Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${app.backup.pg-dump}") String pgDump,
            @Value("${app.backup.gpg}") String gpg) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.pgDump = pgDump;
        this.gpg = gpg;
    }

    @Override
    public String extension() {
        return ".dump.gpg";
    }

    @Override
    public void dump(Path target, String passphrase) {
        Database db = parse(jdbcUrl);
        Path work = null;
        try {
            work = Files.createTempDirectory("intermanage-backup-");
            Path passFile = privateFile(work.resolve("passphrase"));
            Files.writeString(passFile, passphrase, StandardCharsets.UTF_8);
            Path gpgHome = Files.createDirectory(work.resolve("gnupg"));
            restrict(gpgHome, "rwx------");
            Path dumpLog = work.resolve("pg_dump.log");
            Path gpgLog = work.resolve("gpg.log");

            ProcessBuilder dump = new ProcessBuilder(pgDump, "--format=custom", "--no-owner", "--no-privileges",
                    "--no-password", "--host", db.host(), "--port", db.port(), "--username", username,
                    "--dbname", db.name())
                    .redirectError(dumpLog.toFile());
            dump.environment().put("PGPASSWORD", password);
            ProcessBuilder encrypt = new ProcessBuilder(gpg, "--batch", "--yes", "--quiet", "--homedir",
                    gpgHome.toString(), "--pinentry-mode", "loopback", "--passphrase-file", passFile.toString(),
                    "--symmetric", "--cipher-algo", "AES256", "--compress-algo", "none",
                    "--output", target.toString())
                    .redirectError(gpgLog.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);

            List<Process> pipeline = start(List.of(dump, encrypt));
            for (Process process : pipeline) {
                if (!process.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
                    pipeline.forEach(Process::destroyForcibly);
                    throw new BackupException("Zrzut bazy trwał dłużej niż " + TIMEOUT.toHours() + " h i został "
                            + "przerwany");
                }
            }
            if (pipeline.get(0).exitValue() != 0) {
                throw new BackupException("pg_dump zakończył się błędem: " + tail(dumpLog));
            }
            if (pipeline.get(1).exitValue() != 0) {
                throw new BackupException("Szyfrowanie kopii (gpg) zakończyło się błędem: " + tail(gpgLog));
            }
        } catch (IOException e) {
            throw new BackupException("Nie udało się przygotować zrzutu bazy: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("Zrzut bazy został przerwany", e);
        } finally {
            delete(work);
        }
    }

    private List<Process> start(List<ProcessBuilder> builders) throws IOException {
        try {
            return ProcessBuilder.startPipeline(builders);
        } catch (IOException e) {
            throw new BackupException("Na serwerze brakuje programu " + pgDump + " albo " + gpg
                    + " (są w obrazie Dockera backendu)", e);
        }
    }

    record Database(String host, String port, String name) {
    }

    /** {@code jdbc:postgresql://host:port/baza?parametry} → host, port, baza. */
    static Database parse(String jdbcUrl) {
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            String name = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            if (uri.getHost() == null || name.isEmpty()) {
                throw new BackupException("Nie rozpoznano adresu bazy z DB_URL");
            }
            return new Database(uri.getHost(), String.valueOf(uri.getPort() < 0 ? 5432 : uri.getPort()), name);
        } catch (URISyntaxException | StringIndexOutOfBoundsException e) {
            throw new BackupException("Nie rozpoznano adresu bazy z DB_URL", e);
        }
    }

    private static Path privateFile(Path path) throws IOException {
        Files.createFile(path);
        restrict(path, "rw-------");
        return path;
    }

    private static void restrict(Path path, String permissions) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }

    private static String tail(Path logFile) {
        try {
            String text = Files.readString(logFile, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return "brak opisu";
            }
            return text.length() > LOG_TAIL ? "…" + text.substring(text.length() - LOG_TAIL) : text;
        } catch (IOException e) {
            return "brak opisu";
        }
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            log.warn("Nie udało się usunąć katalogu roboczego kopii {}", dir, e);
        }
    }
}
