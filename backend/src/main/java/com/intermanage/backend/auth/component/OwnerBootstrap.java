package com.intermanage.backend.auth.component;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.Role;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.RoleRepository;

/** Zakłada pierwsze konto właściciela, jeśli w bazie nie ma żadnego. */
@Component
public class OwnerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public OwnerBootstrap(AppUserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.owner-username}") String username,
            @Value("${app.bootstrap.owner-password:}") String password) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRoleOwnerTrue()) {
            return;
        }
        Role ownerRole = roles.findByOwnerTrue()
                .orElseThrow(() -> new IllegalStateException("Brak roli właściciela - sprawdź migracje Flyway"));

        boolean generated = password == null || password.isBlank();
        String initialPassword = generated ? randomPassword() : password;
        AppUser owner = new AppUser(username, "Właściciel", passwordEncoder.encode(initialPassword), ownerRole);
        // Hasło z logów albo domyślne (login = hasło) jest tymczasowe: po zalogowaniu trzeba ustawić własne.
        boolean temporary = generated || initialPassword.equals(username);
        if (temporary) {
            owner.requirePasswordChange();
        }
        users.save(owner);

        if (generated) {
            log.warn("Utworzono konto właściciela '{}' z wygenerowanym hasłem: {}  (po zalogowaniu trzeba je zmienić)",
                    username, initialPassword);
        } else if (temporary) {
            log.warn("Utworzono konto właściciela '{}' z domyślnym hasłem - po zalogowaniu trzeba je zmienić", username);
        } else {
            log.info("Utworzono konto właściciela '{}'", username);
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
