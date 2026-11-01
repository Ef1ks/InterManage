package com.intermanage.backend.backup.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.backup.component.BackupException;
import com.intermanage.backend.backup.component.GoogleDriveClient;
import com.intermanage.backend.backup.dto.BackupSettingsRequest;
import com.intermanage.backend.backup.dto.BackupSettingsResponse;
import com.intermanage.backend.backup.dto.GoogleAuthorizeResponse;
import com.intermanage.backend.backup.entity.BackupRun;
import com.intermanage.backend.backup.entity.BackupSettings;
import com.intermanage.backend.backup.entity.BackupStatus;
import com.intermanage.backend.backup.mapper.BackupMapper;
import com.intermanage.backend.backup.repository.BackupRunRepository;
import com.intermanage.backend.backup.repository.BackupSettingsRepository;
import com.intermanage.backend.common.component.SecretCipher;
import com.intermanage.backend.common.exception.ApiException;

/** Ustawienia kopii zapasowej i połączenie z kontem Google (OAuth). Same kopie robi {@link BackupRunner}. */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    /** Ile prób automatycznej kopii dziennie, gdy poprzednie się nie udały, i odstęp między nimi. */
    static final int DAILY_ATTEMPTS = 3;
    static final Duration RETRY_AFTER = Duration.ofHours(1);
    private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);
    private static final int HISTORY_SIZE = 30;
    static final String CALLBACK_PATH = "/api/settings/backup/google/callback";
    private static final String PAGE_PATH = "/ustawienia/kopia-zapasowa";

    private final BackupSettingsRepository settingsRepository;
    private final BackupRunRepository runs;
    private final AppUserRepository users;
    private final GoogleDriveClient drive;
    private final SecretCipher cipher;
    private final BackupMapper mapper;
    private final TransactionTemplate transaction;
    private final String frontendUrl;
    private final SecureRandom random = new SecureRandom();

    public BackupService(BackupSettingsRepository settingsRepository, BackupRunRepository runs,
            AppUserRepository users, GoogleDriveClient drive, SecretCipher cipher, BackupMapper mapper,
            TransactionTemplate transaction, @Value("${app.frontend-url}") String frontendUrl) {
        this.settingsRepository = settingsRepository;
        this.runs = runs;
        this.users = users;
        this.drive = drive;
        this.cipher = cipher;
        this.mapper = mapper;
        this.transaction = transaction;
        this.frontendUrl = frontendUrl.replaceFirst("/+$", "");
    }

    /** Zapisane ustawienia; bez zapisanych domyślne (nic nie zapisuje). */
    @Transactional(readOnly = true)
    public BackupSettings settings() {
        return settingsRepository.findById(BackupSettings.ID).orElseGet(BackupSettings::new);
    }

    @Transactional(readOnly = true)
    public BackupSettingsResponse get(boolean running) {
        BackupSettings settings = settings();
        List<BackupRun> history = runs.findByOrderByStartedAtDesc(Limit.of(HISTORY_SIZE));
        return mapper.toResponse(settings, drive.configured(), running || runs.existsByStatus(BackupStatus.RUNNING),
                nextRunAt(settings, ZonedDateTime.now(ZONE)), history);
    }

    @Transactional
    public void save(BackupSettingsRequest request, UUID userId) {
        BackupSettings settings = settings();
        String password = request.password() == null || request.password().isBlank() ? null : request.password();
        if (request.enabled() && !settings.isConnected()) {
            throw ApiException.badRequest("Najpierw połącz konto Google");
        }
        if (request.enabled() && password == null && !settings.isPasswordSet()) {
            throw ApiException.badRequest("Ustaw hasło szyfrowania kopii");
        }
        if (password != null) {
            settings.setPassword(encrypt(password), users.getReferenceById(userId));
        }
        settings.update(request.enabled(), request.runHour(), request.keepCount(), users.getReferenceById(userId));
        settingsRepository.save(settings);
    }

    @Transactional
    public GoogleAuthorizeResponse authorize() {
        if (!drive.configured()) {
            throw ApiException.conflict("Serwer nie ma danych aplikacji Google (GOOGLE_CLIENT_ID, "
                    + "GOOGLE_CLIENT_SECRET) - instrukcja w deploy/README.md");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        BackupSettings settings = settings();
        settings.startOAuth(state, Instant.now().plus(OAUTH_STATE_TTL));
        settingsRepository.save(settings);
        return new GoogleAuthorizeResponse(drive.authorizationUrl(state, redirectUri()));
    }

    /**
     * Powrót z okna zgody Google: sprawdza state, wymienia kod na tokeny i zapisuje konto. Zwraca adres strony
     * ustawień z wynikiem w parametrach ({@code google=polaczono} albo {@code google=blad&komunikat=…}).
     */
    public String callback(String code, String state, String error, UUID userId) {
        Boolean stateValid = transaction.execute(status -> {
            BackupSettings settings = settings();
            boolean valid = settings.consumeOAuthState(state);
            settingsRepository.save(settings);
            return valid;
        });
        if (error != null) {
            return page("blad", "access_denied".equals(error) ? "Nie udzielono zgody w koncie Google"
                    : "Google zwrócił błąd: " + error);
        }
        if (!Boolean.TRUE.equals(stateValid) || code == null) {
            return page("blad", "Logowanie do Google wygasło albo nie pochodzi z tej aplikacji - spróbuj ponownie");
        }
        try {
            GoogleDriveClient.Tokens tokens = drive.exchangeCode(code, redirectUri());
            String email = drive.accountEmail(tokens.accessToken());
            String encrypted = encrypt(tokens.refreshToken());
            transaction.executeWithoutResult(status -> {
                BackupSettings settings = settings();
                settings.connectGoogle(email, encrypted, users.getReferenceById(userId));
                settingsRepository.save(settings);
            });
            return page("polaczono", null);
        } catch (BackupException | ApiException e) {
            log.warn("Połączenie z Google nie powiodło się: {}", e.getMessage());
            return page("blad", e.getMessage());
        }
    }

    public void disconnect(UUID userId) {
        String token = transaction.execute(status -> {
            BackupSettings settings = settings();
            String encrypted = settings.getGoogleRefreshToken();
            settings.disconnectGoogle(users.getReferenceById(userId));
            settingsRepository.save(settings);
            return encrypted;
        });
        if (token != null) {
            try {
                drive.revoke(cipher.decrypt(token));
            } catch (ApiException e) {
                log.info("Nie odczytano tokenu Google do cofnięcia: {}", e.getMessage());
            }
        }
    }

    /** Czy pora na automatyczną kopię: po dzisiejszej godzinie, a dziś jeszcze nie było udanej ani trwającej. */
    boolean due(BackupSettings settings, ZonedDateTime now) {
        if (!ready(settings)) {
            return false;
        }
        Instant from = todayAt(settings, now);
        if (now.toInstant().isBefore(from)) {
            return false;
        }
        List<BackupRun> today = runs.findByManualFalseAndStartedAtGreaterThanEqualOrderByStartedAtDesc(from);
        if (today.isEmpty()) {
            return true;
        }
        BackupRun last = today.getFirst();
        return last.getStatus() == BackupStatus.FAILED && today.size() < DAILY_ATTEMPTS
                && last.getStartedAt().plus(RETRY_AFTER).isBefore(now.toInstant());
    }

    private Instant nextRunAt(BackupSettings settings, ZonedDateTime now) {
        if (!ready(settings)) {
            return null;
        }
        Instant today = todayAt(settings, now);
        if (now.toInstant().isBefore(today)) {
            return today;
        }
        if (runs.findByManualFalseAndStartedAtGreaterThanEqualOrderByStartedAtDesc(today).isEmpty()) {
            return now.toInstant();
        }
        return today.atZone(ZONE).plusDays(1).toInstant();
    }

    private static boolean ready(BackupSettings settings) {
        return settings.isEnabled() && settings.isConnected() && settings.isPasswordSet();
    }

    private static Instant todayAt(BackupSettings settings, ZonedDateTime now) {
        return now.toLocalDate().atTime(settings.getRunHour(), 0).atZone(ZONE).toInstant();
    }

    private String encrypt(String secret) {
        try {
            return cipher.encrypt(secret);
        } catch (ApiException e) {
            throw ApiException.conflict("Serwer nie ma klucza do szyfrowania sekretów (APP_SECRET_KEY) - ustaw go, "
                    + "zanim skonfigurujesz kopię zapasową");
        }
    }

    private String redirectUri() {
        return frontendUrl + CALLBACK_PATH;
    }

    private String page(String result, String message) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(frontendUrl + PAGE_PATH)
                .queryParam("google", result);
        if (message != null) {
            uri.queryParam("komunikat", message);
        }
        return uri.encode().build().toUriString();
    }
}
