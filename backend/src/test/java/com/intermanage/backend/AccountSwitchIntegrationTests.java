package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.RoleRepository;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.security.JwtService;

import jakarta.servlet.http.Cookie;

/** Przełączanie kont w przeglądarce: konta w tle, PIN, przejście bez PIN-u, blokada PIN-u po błędnych próbach. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class AccountSwitchIntegrationTests {

    private static final String PASSWORD = "haslo-konta-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtService jwtService;

    @Test
    void switchesBetweenAccountsWithPin() throws Exception {
        String anna = createAccount("Anna Nowak");
        String jan = createAccount("Jan Kowalski");

        Cookie annaToken = login(anna);
        mvc.perform(put("/api/auth/pin").cookie(annaToken).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"zle\",\"pin\":\"1234\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/auth/pin").cookie(annaToken).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"pin\":\"12a\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/auth/pin").cookie(annaToken).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"pin\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPin").value(true))
                .andExpect(jsonPath("$.pinRequired").value(true));

        // Anna dodaje konto Jana: Jan jest aktywny, Anna zostaje zalogowana w tle.
        MvcResult added = mvc.perform(post("/api/auth/accounts").cookie(annaToken).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + jan + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(jan))
                .andReturn();
        Cookie janToken = cookie(added, JwtService.COOKIE_NAME);
        Cookie background = cookie(added, JwtService.ACCOUNTS_COOKIE_NAME);

        mvc.perform(get("/api/auth/me").cookie(janToken)).andExpect(jsonPath("$.username").value(jan));
        mvc.perform(get("/api/auth/accounts").cookie(janToken, background))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value(anna))
                .andExpect(jsonPath("$[0].unlock").value("PIN"));
        String annaId = id(anna);

        // Bez PIN-u albo ze złym PIN-em przejścia nie ma.
        mvc.perform(post("/api/auth/accounts/" + annaId + "/switch").cookie(janToken, background).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(switchRequest(annaId, "{\"pin\":\"0000\"}").cookie(janToken, background))
                .andExpect(status().isBadRequest());

        MvcResult switched = mvc.perform(switchRequest(annaId, "{\"pin\":\"1234\"}").cookie(janToken, background))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(anna))
                .andReturn();
        Cookie annaAgain = cookie(switched, JwtService.COOKIE_NAME);
        Cookie janInBackground = cookie(switched, JwtService.ACCOUNTS_COOKIE_NAME);
        mvc.perform(get("/api/auth/me").cookie(annaAgain)).andExpect(jsonPath("$.username").value(anna));

        // Jan nie ma PIN-u, więc przejście na niego wymaga hasła.
        mvc.perform(get("/api/auth/accounts").cookie(annaAgain, janInBackground))
                .andExpect(jsonPath("$[0].username").value(jan))
                .andExpect(jsonPath("$[0].unlock").value("PASSWORD"));
        mvc.perform(switchRequest(id(jan), "{\"password\":\"zle\"}").cookie(annaAgain, janInBackground))
                .andExpect(status().isBadRequest());

        // Po wylogowaniu aktywnego konta Jan dalej jest w tle i da się na niego przejść ze strony logowania.
        mvc.perform(switchRequest(id(jan), "{\"password\":\"" + PASSWORD + "\"}").cookie(janInBackground))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(jan));

        // Wylogowanie konta w tle zdejmuje je z listy.
        MvcResult removed = mvc.perform(delete("/api/auth/accounts/" + id(jan)).cookie(annaAgain, janInBackground)
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(removed.getResponse().getCookie(JwtService.ACCOUNTS_COOKIE_NAME).getMaxAge()).isZero();
    }

    @Test
    void accountWithoutPinRequirementSwitchesWithoutPin() throws Exception {
        String anna = createAccount("Anna Bez Pinu");
        String jan = createAccount("Jan Drugi");
        Cookie annaToken = login(anna);
        mvc.perform(put("/api/auth/pin/required").cookie(annaToken).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"required\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinRequired").value(false));

        MvcResult added = mvc.perform(post("/api/auth/accounts").cookie(annaToken).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + jan + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie janToken = cookie(added, JwtService.COOKIE_NAME);
        Cookie background = cookie(added, JwtService.ACCOUNTS_COOKIE_NAME);

        mvc.perform(get("/api/auth/accounts").cookie(janToken, background))
                .andExpect(jsonPath("$[0].unlock").value("NONE"));
        mvc.perform(post("/api/auth/accounts/" + id(anna) + "/switch").cookie(janToken, background).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(anna));
    }

    @Test
    void tooManyWrongPinsRequirePassword() throws Exception {
        String anna = createAccount("Anna Blokada");
        String jan = createAccount("Jan Blokada");
        Cookie annaToken = login(anna);
        mvc.perform(put("/api/auth/pin").cookie(annaToken).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"pin\":\"4321\"}"))
                .andExpect(status().isOk());
        MvcResult added = mvc.perform(post("/api/auth/accounts").cookie(annaToken).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + jan + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        Cookie janToken = cookie(added, JwtService.COOKIE_NAME);
        Cookie background = cookie(added, JwtService.ACCOUNTS_COOKIE_NAME);
        String annaId = id(anna);

        for (int i = 0; i < 5; i++) {
            mvc.perform(switchRequest(annaId, "{\"pin\":\"9999\"}").cookie(janToken, background))
                    .andExpect(status().isBadRequest());
        }
        // Poprawny PIN już nie wystarcza, dopiero hasło.
        mvc.perform(switchRequest(annaId, "{\"pin\":\"4321\"}").cookie(janToken, background))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/accounts").cookie(janToken, background))
                .andExpect(jsonPath("$[0].unlock").value("PASSWORD"));
        mvc.perform(switchRequest(annaId, "{\"password\":\"" + PASSWORD + "\"}").cookie(janToken, background))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/pin").cookie(annaToken))
                .andExpect(jsonPath("$.hasPin").value(true));
    }

    @Test
    void accountOpenedBeforeLockHourGoesToBackground() throws Exception {
        String anna = createAccount("Anna Po Pracy");
        String jan = createAccount("Jan Po Pracy");
        Cookie annaToken = login(anna);
        mvc.perform(put("/api/auth/pin").cookie(annaToken).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"pin\":\"2468\"}"))
                .andExpect(status().isOk());
        MvcResult added = mvc.perform(post("/api/auth/accounts").cookie(annaToken).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + jan + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie background = cookie(added, JwtService.ACCOUNTS_COOKIE_NAME);

        // Konto otwarte teraz działa.
        mvc.perform(get("/api/auth/me").cookie(cookie(added, JwtService.COOKIE_NAME), background))
                .andExpect(status().isOk());

        // Jan otwarty przedwczoraj: przed ostatnią 17:00, więc idzie do tła, a żądanie jest bez logowania.
        Cookie janStale = new Cookie(JwtService.COOKIE_NAME,
                jwtService.issue(principal(jan), false, Instant.now().minus(Duration.ofDays(2))).getTokenValue());
        MvcResult locked = mvc.perform(get("/api/auth/me").cookie(janStale, background))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(locked.getResponse().getCookie(JwtService.COOKIE_NAME).getMaxAge()).isZero();
        Cookie bothInBackground = cookie(locked, JwtService.ACCOUNTS_COOKIE_NAME);
        mvc.perform(get("/api/auth/accounts").cookie(bothInBackground))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].username").value(jan))
                .andExpect(jsonPath("$[1].unlock").value("PASSWORD"));

        // Przejście w tym samym żądaniu co blokada widzi już Jana w tle.
        mvc.perform(switchRequest(id(anna), "{\"pin\":\"2468\"}").cookie(janStale, background))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(anna));

        // Konto bez wymogu PIN-u blokada pomija (otwiera się i tak jednym kliknięciem).
        mvc.perform(put("/api/auth/pin/required").cookie(annaToken).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"required\":false}"))
                .andExpect(status().isOk());
        Cookie annaStale = new Cookie(JwtService.COOKIE_NAME,
                jwtService.issue(principal(anna), false, Instant.now().minus(Duration.ofDays(2))).getTokenValue());
        mvc.perform(get("/api/auth/me").cookie(annaStale))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(anna));
    }

    private AppUserPrincipal principal(String username) {
        return AppUserPrincipal.from(users.findWithRoleById(UUID.fromString(id(username))).orElseThrow());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder switchRequest(String userId,
            String body) {
        return post("/api/auth/accounts/" + userId + "/switch").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Konto z rolą Właściciel (nie potrzebuje sklepu, żeby się zalogować). */
    private String createAccount(String displayName) {
        String username = "konto" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new AppUser(username, displayName, passwordEncoder.encode(PASSWORD),
                roles.findByOwnerTrue().orElseThrow()));
        return username;
    }

    private String id(String username) {
        return users.findAll().stream().filter(u -> u.getUsername().equals(username)).findFirst().orElseThrow()
                .getId().toString();
    }

    private Cookie login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return cookie(result, JwtService.COOKIE_NAME);
    }

    private static Cookie cookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertThat(cookie).as(name).isNotNull();
        assertThat(cookie.getValue()).as(name).isNotBlank();
        return cookie;
    }
}
