package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.backup.component.BackupException;
import com.intermanage.backend.backup.component.DatabaseDumper;
import com.intermanage.backend.backup.component.GoogleDriveClient;
import com.intermanage.backend.mail.component.MailTransport;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** Kopia zapasowa: połączenie z Google (OAuth state), ustawienia, kopia w tle, limit kopii i mail o błędzie. */
@SpringBootTest(properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.frontend-url=https://intermanage.example"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class})
class BackupIntegrationTests {

    private static final String URL = "/api/settings/backup";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    DatabaseDumper dumper;

    @MockitoBean
    GoogleDriveClient drive;

    // Bez prawdziwej wysyłki: backend/.env może wskazywać na SMTP.
    @MockitoBean
    MailTransport mailTransport;

    private Cookie owner;
    private final AtomicInteger uploads = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        reset(dumper, drive);
        owner = login();
        when(drive.configured()).thenReturn(true);
        when(drive.authorizationUrl(anyString(), anyString()))
                .thenAnswer(call -> "https://accounts.google.com/o/oauth2/v2/auth?state=" + call.getArgument(0)
                        + "&redirect_uri=" + call.getArgument(1));
        when(drive.exchangeCode(eq("kod"), anyString()))
                .thenReturn(new GoogleDriveClient.Tokens("dostep", "odswiezanie"));
        when(drive.accountEmail("dostep")).thenReturn("sklep@gmail.com");
        when(drive.accessToken("odswiezanie")).thenReturn("dostep");
        when(drive.ensureFolder(eq("dostep"), any())).thenReturn("folder-1");
        when(drive.upload(eq("dostep"), eq("folder-1"), anyString(), any()))
                .thenAnswer(call -> "plik-" + uploads.incrementAndGet());
        when(dumper.extension()).thenReturn(".dump.gpg");
        doAnswer(call -> Files.write(call.<Path>getArgument(0), new byte[1234])).when(dumper)
                .dump(any(), eq("haslo-do-kopii-123"));
    }

    @Test
    void connectsConfiguresAndBacksUp() throws Exception {
        // Bez Google nie da się włączyć kopii ani zrobić jej ręcznie.
        mvc.perform(post(URL + "/runs").cookie(owner).with(csrf())).andExpect(status().isBadRequest());

        connect();
        mvc.perform(get(URL).cookie(owner))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.googleEmail").value("sklep@gmail.com"))
                .andExpect(jsonPath("$.googleConnectedBy").value("Właściciel"));

        // Włączenie wymaga hasła, hasło min. 12 znaków.
        save(true, null).andExpect(status().isBadRequest());
        save(true, "krotkie").andExpect(status().isBadRequest());
        save(true, "haslo-do-kopii-123").andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
        assertThat(jdbc.queryForObject("select password_encrypted from backup_settings", String.class))
                .doesNotContain("haslo-do-kopii");
        assertThat(jdbc.queryForObject("select google_refresh_token from backup_settings", String.class))
                .isNotEqualTo("odswiezanie");

        runAndAwait("SUCCEEDED");
        mvc.perform(get(URL).cookie(owner))
                .andExpect(jsonPath("$.runs[0].manual").value(true))
                .andExpect(jsonPath("$.runs[0].sizeBytes").value(1234))
                .andExpect(jsonPath("$.runs[0].fileName").value(containsString(".dump.gpg")))
                .andExpect(jsonPath("$.runs[0].startedBy").value("Właściciel"));

        // Limit 1 kopii: po drugiej pierwsza idzie do kosza Dysku.
        mvc.perform(put(URL).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"runHour\":3,\"keepCount\":1}")).andExpect(status().isOk());
        runAndAwait("SUCCEEDED");
        verify(drive).trash("dostep", "plik-1");
        mvc.perform(get(URL).cookie(owner))
                .andExpect(jsonPath("$.runs[0].trashedAt").isEmpty())
                .andExpect(jsonPath("$.runs[1].trashedAt").isNotEmpty());

        // Błąd zrzutu: wpis w historii i mail do właściciela.
        jdbc.update("update users set email = 'wlasciciel@example.com' where username = 'wlasciciel'");
        doThrow(new BackupException("pg_dump zakończył się błędem: brak połączenia")).when(dumper)
                .dump(any(), anyString());
        runAndAwait("FAILED");
        mvc.perform(get(URL).cookie(owner))
                .andExpect(jsonPath("$.runs[0].error").value("pg_dump zakończył się błędem: brak połączenia"));
        assertThat(jdbc.queryForObject("""
                select count(*) from outgoing_mails
                where kind = 'BACKUP_FAILED' and recipient_email = 'wlasciciel@example.com'""", Integer.class))
                .isEqualTo(1);

        // Odłączenie wyłącza kopie, historia zostaje.
        mvc.perform(delete(URL + "/google").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.runs.length()").value(3));
        verify(drive).revoke("odswiezanie");
    }

    @Test
    void callbackRejectsForeignState() throws Exception {
        mvc.perform(post(URL + "/google/authorize").cookie(owner).with(csrf())).andExpect(status().isOk());
        mvc.perform(get(URL + "/google/callback").param("code", "kod").param("state", "cudzy").cookie(owner))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("google=blad")));
        mvc.perform(get(URL + "/google/callback").param("error", "access_denied").param("state", "x").cookie(owner))
                .andExpect(header().string("Location", containsString("google=blad")));
    }

    private void connect() throws Exception {
        String json = mvc.perform(post(URL + "/google/authorize").cookie(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString(
                        "redirect_uri=https://intermanage.example/api/settings/backup/google/callback")))
                .andReturn().getResponse().getContentAsString();
        String state = UriComponentsBuilder.fromUri(URI.create(JsonPath.read(json, "$.url"))).build()
                .getQueryParams().getFirst("state");
        mvc.perform(get(URL + "/google/callback").param("code", "kod").param("state", state).cookie(owner))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://intermanage.example/ustawienia/kopia-zapasowa?google=polaczono"));
        // State jest jednorazowy.
        mvc.perform(get(URL + "/google/callback").param("code", "kod").param("state", state).cookie(owner))
                .andExpect(header().string("Location", containsString("google=blad")));
    }

    private ResultActions save(boolean enabled, String password) throws Exception {
        return mvc.perform(put(URL).cookie(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + ",\"runHour\":3,\"keepCount\":30,\"password\":"
                        + (password == null ? "null" : "\"" + password + "\"") + "}"));
    }

    private void runAndAwait(String expected) throws Exception {
        mvc.perform(post(URL + "/runs").cookie(owner).with(csrf())).andExpect(status().isAccepted());
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String json = mvc.perform(get(URL).cookie(owner)).andReturn().getResponse().getContentAsString();
            if (!(Boolean) JsonPath.read(json, "$.running")) {
                assertThat((String) JsonPath.read(json, "$.runs[0].status")).isEqualTo(expected);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Kopia nie skończyła się w 10 s");
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(JwtService.COOKIE_NAME);
    }
}
