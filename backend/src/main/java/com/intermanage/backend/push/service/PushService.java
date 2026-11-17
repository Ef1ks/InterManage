package com.intermanage.backend.push.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.push.component.ExpoPushClient;
import com.intermanage.backend.push.entity.UserPushToken;
import com.intermanage.backend.push.repository.UserPushTokenRepository;

/**
 * Powiadomienia push do aplikacji mobilnej. Urządzenie zgłasza swój token po zalogowaniu; {@link #notify} wysyła do
 * wszystkich urządzeń wskazanych kont, po zatwierdzeniu bieżącej transakcji (wycofana zmiana niczego nie wysyła) i w
 * tle, więc nie spowalnia żądania. Tokeny odrzucone przez usługę dostają znacznik.
 */
@Service
public class PushService {

    private static final Set<String> PLATFORMS = Set.of("ios", "android");

    private final UserPushTokenRepository tokens;
    private final AppUserRepository users;
    private final ExpoPushClient client;
    private final TransactionTemplate transactions;

    public PushService(UserPushTokenRepository tokens, AppUserRepository users, ExpoPushClient client,
            TransactionTemplate transactions) {
        this.tokens = tokens;
        this.users = users;
        this.client = client;
        this.transactions = transactions;
    }

    /** Token urządzenia zalogowanego konta; ten sam token na innym koncie przechodzi na to konto. */
    @Transactional
    public void register(UUID userId, String token, String platform) {
        String value = token == null ? "" : token.strip();
        if (value.isEmpty() || value.length() > 300) {
            throw ApiException.badRequest("Nieprawidłowy token powiadomień");
        }
        if (!PLATFORMS.contains(platform)) {
            throw ApiException.badRequest("Platforma to ios albo android");
        }
        UserPushToken current = tokens.findActive(value).orElse(null);
        if (current != null && current.getUser().getId().equals(userId)) {
            current.seen();
            return;
        }
        if (current != null) {
            current.markDeleted();
            tokens.flush();
        }
        tokens.save(new UserPushToken(users.getReferenceById(userId), value, platform));
    }

    /** Wylogowanie na urządzeniu: token przestaje dostawać powiadomienia tego konta. */
    @Transactional
    public void unregister(UUID userId, String token) {
        tokens.findActive(token == null ? "" : token.strip())
                .filter(found -> found.getUser().getId().equals(userId))
                .ifPresent(UserPushToken::markDeleted);
    }

    @Transactional(readOnly = true)
    public List<UUID> usersWithTokens() {
        return tokens.findUserIdsWithTokens();
    }

    /**
     * Powiadomienie do kont (wszystkie ich urządzenia). {@code data.url}: ekran aplikacji do otwarcia po dotknięciu,
     * np. {@code /requests}.
     */
    public void notify(Collection<UUID> userIds, String title, String body, Map<String, String> data) {
        if (userIds.isEmpty()) {
            return;
        }
        List<UUID> recipients = List.copyOf(userIds);
        Runnable send = () -> Thread.ofVirtual().name("push").start(() -> deliver(recipients, title, body, data));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void deliver(List<UUID> userIds, String title, String body, Map<String, String> data) {
        List<String> targets = transactions.execute(status -> tokens.findActiveOfUsers(userIds).stream()
                .map(UserPushToken::getToken).toList());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        List<String> gone = client.send(targets.stream()
                .map(token -> new ExpoPushClient.Message(token, title, body, data)).toList());
        if (!gone.isEmpty()) {
            transactions.executeWithoutResult(status -> tokens.findActiveByTokens(gone)
                    .forEach(UserPushToken::markDeleted));
        }
    }
}
