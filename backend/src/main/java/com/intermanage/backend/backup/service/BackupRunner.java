package com.intermanage.backend.backup.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.backup.component.BackupException;
import com.intermanage.backend.backup.component.DatabaseDumper;
import com.intermanage.backend.backup.component.GoogleDriveClient;
import com.intermanage.backend.backup.entity.BackupRun;
import com.intermanage.backend.backup.entity.BackupSettings;
import com.intermanage.backend.backup.repository.BackupRunRepository;
import com.intermanage.backend.backup.repository.BackupSettingsRepository;
import com.intermanage.backend.common.component.SecretCipher;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mail.component.MailContent;
import com.intermanage.backend.mail.component.MailContent.Detail;
import com.intermanage.backend.mail.entity.MailKind;
import com.intermanage.backend.mail.service.MailService;

/**
 * Robi kopię: zaszyfrowany zrzut bazy na dysk serwera, wysyłka na Dysk Google, potem kopie ponad limit do kosza
 * Dysku. Najwyżej jedna kopia naraz, w tle (wirtualny wątek). Nieudana kopia trafia do historii i do maila
 * do właścicieli.
 */
@Service
public class BackupRunner {

    private static final Logger log = LoggerFactory.getLogger(BackupRunner.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
            .withZone(BackupService.ZONE);
    private static final DateTimeFormatter MAIL_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
            .withZone(BackupService.ZONE);

    private final BackupService service;
    private final BackupSettingsRepository settingsRepository;
    private final BackupRunRepository runs;
    private final AppUserRepository users;
    private final DatabaseDumper dumper;
    private final GoogleDriveClient drive;
    private final SecretCipher cipher;
    private final MailService mail;
    private final TransactionTemplate transaction;
    private final boolean scheduleEnabled;
    private final Path workDir;
    private final String frontendUrl;
    private final AtomicBoolean running = new AtomicBoolean();

    public BackupRunner(BackupService service, BackupSettingsRepository settingsRepository, BackupRunRepository runs,
            AppUserRepository users, DatabaseDumper dumper, GoogleDriveClient drive, SecretCipher cipher,
            MailService mail, TransactionTemplate transaction,
            @Value("${app.backup.schedule-enabled}") boolean scheduleEnabled,
            @Value("${app.backup.work-dir}") Path workDir,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.service = service;
        this.settingsRepository = settingsRepository;
        this.runs = runs;
        this.users = users;
        this.dumper = dumper;
        this.drive = drive;
        this.cipher = cipher;
        this.mail = mail;
        this.transaction = transaction;
        this.scheduleEnabled = scheduleEnabled;
        this.workDir = workDir;
        this.frontendUrl = frontendUrl.replaceFirst("/+$", "");
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Proces kopii nie przeżywa restartu: próby, które zostały „w trakcie”, oznacza jako nieudane. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        Integer count = transaction.execute(status ->
                runs.failRunning(Instant.now(), "Przerwana: serwer został uruchomiony ponownie w trakcie kopii"));
        if (count != null && count > 0) {
            log.warn("Kopie zapasowe przerwane restartem: {}", count);
        }
    }

    @Scheduled(fixedDelayString = "${app.backup.check-interval}", initialDelayString = "PT3M")
    public void runScheduled() {
        if (!scheduleEnabled || running.get()) {
            return;
        }
        BackupSettings settings = service.settings();
        if (service.due(settings, ZonedDateTime.now(BackupService.ZONE))) {
            start(null);
        }
    }

    /** „Zrób kopię teraz”: zaczyna w tle i od razu wraca. */
    public void startManual(UUID userId) {
        BackupSettings settings = service.settings();
        if (!settings.isConnected()) {
            throw ApiException.badRequest("Najpierw połącz konto Google");
        }
        if (!settings.isPasswordSet()) {
            throw ApiException.badRequest("Ustaw hasło szyfrowania kopii");
        }
        if (!start(userId)) {
            throw ApiException.conflict("Kopia zapasowa właśnie się robi - poczekaj, aż się skończy");
        }
    }

    private boolean start(UUID userId) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            UUID runId = transaction.execute(status -> runs.save(
                    new BackupRun(userId == null ? null : users.getReferenceById(userId))).getId());
            Thread.ofVirtual().name("backup-" + runId).start(() -> execute(runId));
            return true;
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    private void execute(UUID runId) {
        Path file = null;
        try {
            Secrets secrets = transaction.execute(status -> secrets(service.settings()));
            String name = "intermanage_" + FILE_TIME.format(Instant.now()) + dumper.extension();
            Files.createDirectories(workDir);
            file = Files.createTempFile(workDir, "intermanage-backup-", dumper.extension());
            dumper.dump(file, secrets.password());
            long size = Files.size(file);

            String accessToken = drive.accessToken(secrets.refreshToken());
            String folderId = drive.ensureFolder(accessToken, secrets.folderId());
            if (!folderId.equals(secrets.folderId())) {
                transaction.executeWithoutResult(status -> {
                    BackupSettings settings = service.settings();
                    settings.setGoogleFolderId(folderId);
                    settingsRepository.save(settings);
                });
            }
            String fileId = drive.upload(accessToken, folderId, name, file);
            transaction.executeWithoutResult(status -> {
                BackupRun run = runs.findById(runId).orElseThrow();
                run.succeed(name, size, secrets.email(), fileId);
            });
            log.info("Kopia zapasowa {} ({} B) wysłana na Dysk Google", name, size);
            trashOld(accessToken, secrets);
        } catch (BackupException e) {
            fail(runId, e.getMessage());
        } catch (IOException e) {
            fail(runId, "Nie udało się zapisać pliku kopii na serwerze: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Kopia zapasowa nie powiodła się", e);
            fail(runId, "Nieoczekiwany błąd: " + e.getMessage());
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.warn("Nie usunięto pliku kopii {}", file, e);
                }
            }
            running.set(false);
        }
    }

    private record Secrets(String password, String refreshToken, String email, String folderId, int keepCount) {
    }

    private Secrets secrets(BackupSettings settings) {
        if (!settings.isConnected() || !settings.isPasswordSet()) {
            throw new BackupException("Kopia nie jest skonfigurowana: brak połączenia z Google albo hasła");
        }
        try {
            return new Secrets(cipher.decrypt(settings.getPasswordEncrypted()),
                    cipher.decrypt(settings.getGoogleRefreshToken()), settings.getGoogleEmail(),
                    settings.getGoogleFolderId(), settings.getKeepCount());
        } catch (ApiException e) {
            throw new BackupException("Nie da się odczytać zapisanych sekretów kopii - klucz APP_SECRET_KEY zmienił "
                    + "się od zapisu. Ustaw hasło kopii i połącz konto Google ponownie.");
        }
    }

    /** Kopie ponad limit (tylko z bieżącego konta Google) idą do kosza Dysku. Błąd tu nie psuje udanej kopii. */
    private void trashOld(String accessToken, Secrets secrets) {
        List<BackupRun> onDrive = transaction.execute(status -> runs.findOnDrive().stream()
                .filter(run -> secrets.email().equalsIgnoreCase(run.getGoogleEmail())).toList());
        for (BackupRun old : onDrive.stream().skip(secrets.keepCount()).toList()) {
            try {
                drive.trash(accessToken, old.getDriveFileId());
                transaction.executeWithoutResult(status -> runs.findById(old.getId()).orElseThrow().markTrashed());
            } catch (BackupException e) {
                log.warn("Stara kopia {} nie trafiła do kosza: {}", old.getFileName(), e.getMessage());
            }
        }
    }

    private void fail(UUID runId, String error) {
        log.warn("Kopia zapasowa nie powiodła się: {}", error);
        transaction.executeWithoutResult(status -> {
            BackupRun run = runs.findById(runId).orElseThrow();
            run.fail(error);
            MailContent content = new MailContent()
                    .heading("Kopia zapasowa się nie udała")
                    .paragraph("Kopia bazy Intermanage na Dysk Google nie powiodła się. "
                            + (run.isManual() ? "" : "Automatyczna kopia spróbuje ponownie za godzinę, najwyżej "
                                    + BackupService.DAILY_ATTEMPTS + " razy dziennie."))
                    .details(new Detail("Początek", MAIL_TIME.format(run.getStartedAt())),
                            new Detail("Błąd", error))
                    .button("Otwórz ustawienia kopii", frontendUrl + "/ustawienia/kopia-zapasowa");
            for (AppUser owner : users.findActiveOwnersWithEmail()) {
                mail.queue(MailKind.BACKUP_FAILED, owner.getEmail(), owner.getDisplayName(),
                        "Kopia zapasowa się nie udała", content);
            }
        });
    }
}
