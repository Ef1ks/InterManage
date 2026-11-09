package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
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

/** „Do pobrania”: właściciel zakłada pozycję i wersje pliku, każdy zalogowany pobiera; usunięcie to znacznik. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class DownloadIntegrationTests {

    private static final String SETTINGS = "/api/settings/downloads";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = login();
        jdbc.update("update downloads set removed_at = now() where removed_at is null");
    }

    @Test
    void ownerPublishesAndEveryoneDownloads() throws Exception {
        Cookie employee = employee();

        // Pracownik nie zmienia listy.
        mvc.perform(multipart(SETTINGS).param("title", "X").cookie(employee).with(csrf()))
                .andExpect(status().isForbidden());

        // Pozycja bez pliku: właściciel ją widzi, pracownik nie.
        String created = mvc.perform(multipart(SETTINGS).param("title", "AutoJob")
                        .param("description", "Odtwarzacz makr").cookie(owner).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$[0].id");
        assertThat(list(employee)).isEmpty();

        upload(id, "AutoJob.exe", new byte[] {1, 2, 3}, "v19");
        List<Map<String, Object>> visible = list(employee);
        assertThat(visible).hasSize(1);
        assertThat(JsonPath.<String>read(visible.get(0), "$.file.version")).isEqualTo("v19");
        assertThat(JsonPath.<String>read(visible.get(0), "$.file.sha256"))
                .isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");

        // Nowa wersja zastępuje poprzednią, która zostaje w bazie.
        upload(id, "C:\\pliki\\AutoJob.exe", new byte[] {4, 5}, "v20");
        byte[] body = mvc.perform(get("/api/downloads/" + id + "/file").cookie(employee))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment; filename=\"AutoJob.exe\"")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).containsExactly(4, 5);
        assertThat(jdbc.queryForObject("select count(*) from download_files where download_id = ?::uuid",
                Integer.class, id)).isEqualTo(2);

        mvc.perform(put(SETTINGS + "/" + id).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\"}")).andExpect(status().isBadRequest());

        // Usunięcie to znacznik, „Przywróć” oddaje pozycję razem z plikiem.
        mvc.perform(delete(SETTINGS + "/" + id).cookie(owner).with(csrf())).andExpect(status().isOk());
        assertThat(list(employee)).isEmpty();
        mvc.perform(get("/api/downloads/" + id + "/file").cookie(employee)).andExpect(status().isNotFound());
        mvc.perform(post(SETTINGS + "/" + id + "/restore").cookie(owner).with(csrf())).andExpect(status().isOk());
        assertThat(list(employee)).hasSize(1);
    }

    @Test
    void newVersionIsAnnouncedWithChangelogUntilSeen() throws Exception {
        Cookie employee = employee();
        String id = JsonPath.read(mvc.perform(multipart(SETTINGS)
                        .file(new MockMultipartFile("file", "AutoJob.exe", "application/octet-stream", new byte[] {1}))
                        .param("title", "AutoJob").param("version", "v36").cookie(owner).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$[0].id");

        // Kto wgrał, ten wersję zna; pracownik dostaje baner.
        assertThat(JsonPath.<Boolean>read(list(owner).get(0), "$.newVersion")).isFalse();
        assertThat(JsonPath.<Boolean>read(list(employee).get(0), "$.newVersion")).isTrue();

        // Pobranie zdejmuje baner.
        mvc.perform(get("/api/downloads/" + id + "/file").cookie(employee)).andExpect(status().isOk());
        assertThat(JsonPath.<Boolean>read(list(employee).get(0), "$.newVersion")).isFalse();

        // Nowa wersja z listą zmian: baner wraca, wpisy w kolejności Dodano, Zmieniono, Naprawiono.
        mvc.perform(multipart(HttpMethod.PUT, SETTINGS + "/" + id + "/file")
                        .file(new MockMultipartFile("file", "AutoJob.exe", "application/octet-stream", new byte[] {2}))
                        .param("version", "v37").param("added", "- Makro korekty\n\n• Skrót Alt+R")
                        .param("fixed", "Esc zatrzymuje makro").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        Map<String, Object> item = list(employee).get(0);
        assertThat(JsonPath.<Boolean>read(item, "$.newVersion")).isTrue();
        assertThat(JsonPath.<List<String>>read(item, "$.file.changes[*].text"))
                .containsExactly("Makro korekty", "Skrót Alt+R", "Esc zatrzymuje makro");
        assertThat(JsonPath.<List<String>>read(item, "$.file.changes[*].kind"))
                .containsExactly("ADDED", "ADDED", "FIXED");

        // „Mam już tę wersję” też zdejmuje baner.
        mvc.perform(post("/api/downloads/" + id + "/seen").cookie(employee).with(csrf())).andExpect(status().isOk());
        assertThat(JsonPath.<Boolean>read(list(employee).get(0), "$.newVersion")).isFalse();

        // Poprawka listy zmian nie ogłasza wersji drugi raz, stare wpisy dostają znacznik.
        mvc.perform(put(SETTINGS + "/" + id + "/changes").cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"changed\":\"Szybsze makra\"}"))
                .andExpect(status().isOk());
        item = list(employee).get(0);
        assertThat(JsonPath.<Boolean>read(item, "$.newVersion")).isFalse();
        assertThat(JsonPath.<List<String>>read(item, "$.file.changes[*].text")).containsExactly("Szybsze makra");
        assertThat(jdbc.queryForObject("select count(*) from download_file_changes where removed_at is not null"
                + " and file_id in (select id from download_files where download_id = ?::uuid)", Integer.class, id))
                .isEqualTo(3);

        // Wersja wgrana po cichu nie daje banera.
        mvc.perform(multipart(HttpMethod.PUT, SETTINGS + "/" + id + "/file")
                        .file(new MockMultipartFile("file", "AutoJob.exe", "application/octet-stream", new byte[] {3}))
                        .param("version", "v37.1").param("announce", "false").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        assertThat(JsonPath.<Boolean>read(list(employee).get(0), "$.newVersion")).isFalse();

        // Historia: wszystkie wersje od najnowszej, każda ze swoją listą zmian.
        String versions = mvc.perform(get("/api/downloads/" + id + "/versions").cookie(employee))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(versions, "$[*].version")).containsExactly("v37.1", "v37", "v36");
        assertThat(JsonPath.<List<String>>read(versions, "$[1].changes[*].text")).containsExactly("Szybsze makra");
    }

    private void upload(String id, String name, byte[] content, String version) throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, SETTINGS + "/" + id + "/file")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", content))
                        .param("version", version).cookie(owner).with(csrf()))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> list(Cookie cookie) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/downloads").cookie(cookie)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$");
    }

    private Cookie employee() throws Exception {
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
        String username = "dl" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), JsonPath.read(roleJson, "$.id"));
        jdbc.update("insert into user_stores (user_id, store_id) values (?::uuid, ?::uuid)", userId,
                JsonPath.read(storeJson, "$.id"));
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"haslo-pracownika\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
