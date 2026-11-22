package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.intermanage.backend.auth.security.JwtService;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Zgłoszenia poprawek: zgłasza każdy zalogowany ze zrzutami, prowadzi tylko właściciel; usunięcie to znacznik. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class FeedbackIntegrationTests {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
        jdbc.update("update feedback_reports set deleted_at = now() where deleted_at is null");
    }

    @Test
    void employeeReportsWithScreenshotAndOwnerHandlesIt() throws Exception {
        String storeId = store();
        String otherStore = store();
        Cookie employee = employee(storeId);

        String created = mvc.perform(multipart("/api/feedback")
                        .file(new MockMultipartFile("files", "blob", "image/png", PNG))
                        .param("kind", "BUG").param("description", "  Przycisk zapisu nic nie robi  ")
                        .param("pageUrl", "/wprowadzanie-danych?x=1").param("pageLabel", "Wprowadzanie danych")
                        .param("storeId", storeId).cookie(employee).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        assertThat((String) JsonPath.read(created, "$.description")).isEqualTo("Przycisk zapisu nic nie robi");
        assertThat((String) JsonPath.read(created, "$.storeId")).isEqualTo(storeId);
        assertThat((String) JsonPath.read(created, "$.attachments[0].contentType")).isEqualTo("image/png");
        String attachmentId = JsonPath.read(created, "$.attachments[0].id");

        // Sklep bez dostępu nie trafia do zgłoszenia; plik, który nie jest obrazem, jest odrzucany.
        String foreign = mvc.perform(multipart("/api/feedback").param("description", "Inny sklep")
                        .param("storeId", otherStore).cookie(employee).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(foreign, "$.storeId")).isNull();
        mvc.perform(multipart("/api/feedback").file(new MockMultipartFile("files", "a.txt", "text/plain",
                        "tekst".getBytes())).param("description", "Zły plik").cookie(employee).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/feedback").param("description", " ").cookie(employee).with(csrf()))
                .andExpect(status().isBadRequest());

        // Pracownik widzi swoje, ale nie listę właściciela ani zrzutów.
        String mine = mvc.perform(get("/api/feedback/mine").cookie(employee))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(mine, "$")).hasSize(2);
        mvc.perform(get("/api/settings/feedback").cookie(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings/feedback/" + id + "/attachments/" + attachmentId).cookie(employee))
                .andExpect(status().isForbidden());

        // Właściciel: lista, licznik nowych, zrzut, zmiana stanu z notatką.
        String counts = mvc.perform(get("/api/settings/feedback/counts").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(counts, "$.fresh")).isEqualTo(2);
        byte[] image = mvc.perform(get("/api/settings/feedback/" + id + "/attachments/" + attachmentId).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(image).isEqualTo(PNG);
        String changed = mvc.perform(put("/api/settings/feedback/" + id + "/status").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"note\":\"Poprawione w wersji z 22.11\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(changed, "$.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(changed, "$.ownerNote")).isEqualTo("Poprawione w wersji z 22.11");
        String done = mvc.perform(get("/api/settings/feedback").param("status", "DONE").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(done, "$")).hasSize(1);

        // Usunięcie to znacznik z przywróceniem.
        mvc.perform(delete("/api/settings/feedback/" + id).cookie(owner).with(csrf()))
                .andExpect(status().isNoContent());
        String afterDelete = mvc.perform(get("/api/settings/feedback").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(afterDelete, "$")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from feedback_reports where id = ?::uuid", Integer.class, id))
                .isEqualTo(1);
        mvc.perform(post("/api/settings/feedback/" + id + "/restore").cookie(owner).with(csrf()))
                .andExpect(status().isOk());
        String afterRestore = mvc.perform(get("/api/settings/feedback").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(afterRestore, "$")).hasSize(2);
    }

    private String store() throws Exception {
        String number = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999_999L));
        String json = mvc.perform(post("/api/settings/stores").cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"" + number + "\",\"name\":\"Sklep " + number + "\",\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private Cookie employee(String storeId) throws Exception {
        String username = "fb" + UUID.randomUUID().toString().substring(0, 8);
        String userId = jdbc.queryForObject("""
                insert into users (username, display_name, password_hash, role_id)
                values (?, 'Pracownik', ?, ?::uuid) returning id""", String.class,
                username, passwordEncoder.encode("haslo-pracownika"), TestRanks.role(jdbc, "{}"));
        TestRanks.join(jdbc, userId, storeId);
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"haslo-pracownika\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
