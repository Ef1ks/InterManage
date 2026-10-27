package com.intermanage.backend.ksef.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.intermanage.backend.common.component.SecretCipher;
import com.intermanage.backend.ksef.component.KsefClient;
import com.intermanage.backend.ksef.component.KsefCredentials;
import com.intermanage.backend.ksef.component.KsefRateLimiter;
import com.intermanage.backend.ksef.entity.KsefConnection;

/**
 * Logowanie do KSeF certyfikatem z połączenia sklepu. Token dostępu jest trzymany w pamięci, póki ważny (logowanie to
 * kilka zapytań i weryfikacja certyfikatu), osobno dla każdej firmy (NIP-u, czyli kontekstu w KSeF) - sklepy jednej
 * firmy korzystają z jednego tokenu. Zmiana połączenia któregokolwiek z nich go unieważnia.
 */
@Service
public class KsefSessionService {

    /** Zapas przed końcem ważności - token nie może wygasnąć w trakcie pobierania. */
    private static final Duration MARGIN = Duration.ofMinutes(2);

    private final KsefClient client;
    private final KsefCredentials credentials;
    private final SecretCipher cipher;
    private final KsefRateLimiter limiter;
    private final Map<String, Cached> tokens = new ConcurrentHashMap<>();

    public KsefSessionService(KsefClient client, KsefCredentials credentials, SecretCipher cipher,
            KsefRateLimiter limiter) {
        this.client = client;
        this.credentials = credentials;
        this.cipher = cipher;
        this.limiter = limiter;
    }

    private record Cached(String token, Instant validUntil) {
    }

    /** Token dostępu w kontekście NIP-u połączenia - z pamięci albo po nowym logowaniu tym połączeniem. */
    public String accessToken(KsefConnection connection) {
        Cached cached = tokens.get(connection.getNip());
        if (cached != null && Instant.now().plus(MARGIN).isBefore(cached.validUntil())) {
            return cached.token();
        }
        return login(connection).token();
    }

    /** Logowanie od nowa (test połączenia); błąd KSeF jako {@link KsefClient.KsefException}. */
    public KsefClient.AccessToken login(KsefConnection connection) {
        KsefCredentials.Loaded loaded = credentials.load(connection.getCertificatePem(),
                connection.getPrivateKeyPem(), cipher.decrypt(connection.getPasswordEncrypted()));
        KsefClient.AccessToken token = limiter.call(connection.getNip(), KsefRateLimiter.Operation.AUTH,
                () -> client.authenticate(connection.getNip(), loaded.certificate(), loaded.privateKey()));
        tokens.put(connection.getNip(), new Cached(token.token(), token.validUntil()));
        return token;
    }

    public void forget(String nip) {
        tokens.remove(nip);
    }
}
