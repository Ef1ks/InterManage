package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Zdjęcie w tle aplikacji: każde konto ma swoje, zmiana i usunięcie zostawiają poprzednie do przywrócenia. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class UserBackgroundIntegrationTests {

    private static final String URL = "/api/auth/background";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 4, 5, 6};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void backgroundBelongsToAccountAndCanBeRestored() throws Exception {
        Cookie owner = login("wlasciciel", "haslo-wlasciciela");
        Cookie employee = employee(owner);
        assertThat(JsonPath.<Object>read(me(employee), "$.background")).isNull();

        // PDF to nie zdjęcie.
        mvc.perform(upload(employee, "skan.pdf", new byte[] {0x25, 0x50, 0x44, 0x46, 1}))
                .andExpect(status().isBadRequest());

        String first = mvc.perform(upload(employee, "las.png", PNG)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$.background.id");
        assertThat(JsonPath.<Object>read(first, "$.previousId")).isNull();
        assertThat(JsonPath.<Integer>read(me(employee), "$.background.veilPercent")).isEqualTo(70);
        // Bez R2 (testy) zdjęcie zostaje w bazie i serwuje je backend.
        assertThat(JsonPath.<String>read(first, "$.background.url")).isEqualTo(URL + "/" + firstId);

        mvc.perform(get(URL + "/" + firstId).cookie(employee)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
        // Cudzego zdjęcia nie widać, nawet właścicielowi.
        mvc.perform(get(URL + "/" + firstId).cookie(owner)).andExpect(status().isNotFound());

        mvc.perform(put(URL + "/veil").cookie(employee).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"veilPercent\":96}")).andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/veil").cookie(employee).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"veilPercent\":40}")).andExpect(status().isOk());

        // Nowe zdjęcie bierze przesłonięcie poprzedniego, a poprzednie da się przywrócić.
        String second = mvc.perform(upload(employee, "morze.jpg", JPEG)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondId = JsonPath.read(second, "$.background.id");
        assertThat(JsonPath.<String>read(second, "$.previousId")).isEqualTo(firstId);
        assertThat(JsonPath.<Integer>read(second, "$.background.veilPercent")).isEqualTo(40);

        String removed = mvc.perform(delete(URL).cookie(employee).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(removed, "$.previousId")).isEqualTo(secondId);
        assertThat(JsonPath.<Object>read(me(employee), "$.background")).isNull();

        mvc.perform(post(URL + "/" + firstId + "/restore").cookie(employee).with(csrf())).andExpect(status().isOk());
        assertThat(JsonPath.<String>read(me(employee), "$.background.id")).isEqualTo(firstId);
        assertThat(JsonPath.<Object>read(me(owner), "$.background")).isNull();
        // Nic nie znika z bazy: oba zdjęcia zostają, jedno obowiązuje.
        assertThat(jdbc.queryForObject("""
                select count(*) from user_backgrounds
                where user_id = (select user_id from user_backgrounds where id = ?::uuid)""", Integer.class, firstId))
                .isEqualTo(2);
    }

    private org.springframework.test.web.servlet.RequestBuilder upload(Cookie cookie, String name, byte[] content) {
        return multipart(HttpMethod.PUT, URL).file(new MockMultipartFile("file", name, null, content))
                .cookie(cookie).with(csrf());
    }

    private String me(Cookie cookie) throws Exception {
        return mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Cookie employee(Cookie owner) throws Exception {
        String roleJson = mvc.perform(post("/api/settings/roles").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rola " + UUID.randomUUID() + "\",\"permissions\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String storeJson = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String username = "bg" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId,
                JsonPath.read(storeJson, "$.id"));
        return login(username, "haslo-pracownika");
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
